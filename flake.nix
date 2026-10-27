{
  description = "kmet on Babashka, with the kmet-extensions collection";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    kmet = {
      url = "github:kmetia/kmet-agent/214b551bdff37b4e4a74ca8d380bbb2df2fbaec7";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, kmet }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      perSystem = system:
        let
          pkgs = import nixpkgs { inherit system; };
          extensionNames = builtins.filter
            (name: (builtins.readDir ./extensions).${name} == "directory"
              && builtins.pathExists (./extensions + "/${name}/src/extension.edn"))
            (builtins.attrNames (builtins.readDir ./extensions));
          # Resolve precisely the pinned host's bb.edn dependencies. This is a
          # fixed-output fetch: the subsequent build and launcher need no Maven.
          maven = pkgs.stdenvNoCC.mkDerivation {
            name = "kmet-bb-maven";
            src = kmet;
            nativeBuildInputs = [ pkgs.babashka pkgs.clojure pkgs.cacert ];
            buildPhase = ''
              export HOME="$TMPDIR/home"
              mkdir -p "$HOME" "$out"
              bb --config /dev/null -e '(require (quote clojure.edn)) (spit "deps.edn" (pr-str {:deps (:deps (clojure.edn/read-string (slurp "bb.edn")))}))'
              clojure -Srepro -Sdeps "{:mvn/local-repo \"$out\"}" -Spath
              find "$out" -type f \( -name '*.lastUpdated' -o -name '*.part*' -o -name '*.lock' -o -name '_remote.repositories' \) -delete
            '';
            installPhase = "true";
            outputHashMode = "recursive";
            outputHashAlgo = "sha256";
            outputHash = "sha256-VjJTrIsBptBqgKJByIq9JYoHh/H8/q5geHP3zs1pn2c=";
          };
          staticBabashka = pkgs.fetchurl {
            url = "https://github.com/babashka/babashka/releases/download/v1.13.226/babashka-1.13.226-linux-amd64-static.tar.gz";
            hash = "sha256-v4npCcQCXt8tZpkhyBEHQ1tyRIQNj0MG9YQ5wyijQvk=";
          };
          standalone = pkgs.stdenvNoCC.mkDerivation {
            pname = "kmet-extensions-standalone";
            version = "214b551b";
            nativeBuildInputs = [ pkgs.babashka ];
            dontUnpack = true;
            dontFixup = true; # Never patch the static ELF or its appended ZIP.
            allowedReferences = [ ]; # Fail if a Nix store dependency leaks in.
            buildPhase = ''
              tar xf ${staticBabashka}
              TZ=UTC bb --config /dev/null ${./scripts/package-standalone.bb} \
                ${host}/share/kmet/kmet.jar ${self} payload.jar
              mkdir -p "$out/bin"
              cat bb payload.jar > "$out/bin/kmet-extensions"
              chmod +x "$out/bin/kmet-extensions"
            '';
            installPhase = "true";
          };
          host = pkgs.stdenvNoCC.mkDerivation {
            pname = "kmet-bb";
            version = "214b551b";
            src = kmet;
            nativeBuildInputs = [ pkgs.babashka ];
            buildPhase = ''
              export HOME="$TMPDIR/home"
              mkdir -p "$HOME"
              # --classpath bypasses bb.edn dependency resolution entirely.
              cp=$(find ${maven} -name '*.jar' -print | paste -sd:)
              roots=$(bb --config /dev/null -e '(require (quote clojure.edn) (quote clojure.string)) (println (clojure.string/join ":" (:paths (clojure.edn/read-string (slurp "bb.edn")))))')
              bb --config /dev/null --classpath "$roots:$cp" -e '(require (quote kmet.tasks.build) (quote kmet.app.version)) (with-redefs [kmet.app.version/checkout-version (constantly "dev-g${builtins.substring 0 8 kmet.rev}")] (kmet.tasks.build/uberjar*))'
            '';
            installPhase = ''
              mkdir -p "$out/share/kmet"
              cp target/uberjar-bb.jar "$out/share/kmet/kmet.jar"
            '';
          };
          launcher = pkgs.writeText "kmet-extensions.bb" ''
            (require '[kmet.app.packages :as packages]
                     '[kmet.app.core :as core])

            ;; Add immutable extension paths to the loaded configuration only;
            ;; never write the user's settings or change the project cwd.
            (let [resolve-items packages/resolve-package-items
                  paths [${pkgs.lib.concatMapStringsSep " " (name: ''"${self}/extensions/${name}"'') extensionNames}]]
              (with-redefs [packages/resolve-package-items
                            (fn [user-settings & args]
                              (apply resolve-items
                                     (update user-settings :extensions
                                             #(vec (distinct (concat paths %))))
                                     args))]
                (apply core/-main *command-line-args*)))
          '';
          package = pkgs.writeShellApplication {
            name = "kmet-extensions";
            runtimeInputs = [ pkgs.babashka pkgs.gitMinimal pkgs.bash pkgs.ripgrep ];
            text = ''
              exec bb --config /dev/null --classpath ${host}/share/kmet/kmet.jar ${launcher} "$@"
            '';
          };
        in {
          inherit package standalone;
          portableSmoke = pkgs.runCommand "kmet-extensions-portable-smoke" { } ''
            mkdir -p home scratch
            export HOME="$PWD/home"
            export TMPDIR="$PWD/scratch"
            cp ${standalone}/bin/kmet-extensions ./kmet-extensions
            env PATH=/nonexistent ./kmet-extensions --version | grep -F 'kmet dev-g${builtins.substring 0 8 kmet.rev}'
            env PATH=/nonexistent ./kmet-extensions --help | grep -F 'Usage: kmet'
            test -z "$(ls -A scratch)"
            touch "$out"
          '';
          smoke = pkgs.runCommand "kmet-extensions-smoke" { } ''
            export HOME="$TMPDIR/home"
            mkdir -p "$HOME"
            ${package}/bin/kmet-extensions --version | grep -F 'kmet dev-g${builtins.substring 0 8 kmet.rev}'
            ${package}/bin/kmet-extensions --help | grep -F 'Usage: kmet'
            touch "$out"
          '';
        };
    in {
      packages = forAllSystems (system:
        let built = perSystem system;
        in { launcher = built.package; default = built.package; }
          // nixpkgs.lib.optionalAttrs (system == "x86_64-linux") { standalone = built.standalone; });
      checks = forAllSystems (system:
        { startup = (perSystem system).smoke; }
        // nixpkgs.lib.optionalAttrs (system == "x86_64-linux") {
          standalone = (perSystem system).portableSmoke;
        });
      apps = forAllSystems (system: {
        default = {
          type = "app";
          meta.description = "Run pinned kmet on Babashka with all seven extensions";
          program = "${self.packages.${system}.launcher}/bin/kmet-extensions";
        };
      });
    };
}
