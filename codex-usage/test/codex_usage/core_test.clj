(ns codex-usage.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [codex-usage.core :as usage]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.crypto :as crypto]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]))

(def primary {:used_percent 23.5 :limit_window_seconds 18000 :reset_at 1700000000})
(def weekly {:used_percent 110 :limit_window_seconds 604800 :reset_at 1700500000})
(def payload {:account {:email "test@example.org" :plan "plus"}
              :rate_limit {:primary_window primary :secondary_window weekly}})
(def ctx {:model {:provider :adapter :id "codex" :base-url "https://adapter.test/v1/"}})

(defn api-with-auth []
  (let [{:keys [api state]} (ext/create-nullable-api)]
    {:api (assoc-in api [:models :get-api-key-and-headers]
                    (fn [_] {:ok true :api-key "secret" :headers {"X-Test" "yes"}}))
     :state state}))

(use-fixtures :each (fn [f] (try (f) (finally (usage/shutdown (:api (ext/create-nullable-api)))))))

(deftest parsing-and-presentation
  (let [status (usage/parse-status payload)
        line (usage/format-status-line status)
        card (usage/format-status-card status)]
    (is (str/starts-with? line "76.5%/5h (resets "))
    (is (str/includes? line "  0%/w (resets "))
    (is (str/includes? line " on "))
    (is (str/includes? card "Account:       test@example.org (Plus)"))
    (is (str/includes? card "[███████████████░░░░░] 76.5% left"))
    (is (str/includes? card "Weekly limit: [░░░░░░░░░░░░░░░░░░░░] 0% left")))
  (testing "finite windows only and weekly tolerance"
    (doseq [bad [nil ##NaN ##Inf "10"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (usage/parse-status {:rate_limit {:primary_window (assoc primary :used_percent bad)}}))))
    (is (:weekly (usage/parse-status {:rate_limit {:secondary_window (assoc weekly :limit_window_seconds 600000)}})))
    (is (thrown? clojure.lang.ExceptionInfo (usage/parse-status {}))))
  (testing "weekly primary is not duplicated; quota is clamped"
    (is (= 1 (count (re-seq #"resets" (usage/format-status-line
                                       (usage/parse-status {:rate_limit {:primary_window weekly}}))))))
    (is (str/starts-with? (usage/format-status-line
                           (usage/parse-status {:rate_limit {:primary_window (assoc primary :used_percent -10)}})) "100%/5h"))))

(deftest connections-and-requests
  (let [{:keys [api]} (api-with-auth)
        requests (atom [])]
    (with-redefs [http/request (fn [req] (swap! requests conj req)
                                 {:status 200 :body (json/generate-string payload)})]
      (is (= (:account payload) (:account (usage/fetch-status api ctx)))))
    (is (= "https://adapter.test/v1/codex/usage" (:url (first @requests))))
    (is (= {"Authorization" "Bearer secret" "X-Test" "yes"} (:headers (first @requests))))
    (is (= 15000 (:timeout-ms (first @requests))))
    (with-redefs [http/request (fn [_] {:status 403 :body "denied"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP 403.*denied" (usage/fetch-status api ctx))))
    (is (thrown? clojure.lang.ExceptionInfo (usage/resolve-connection api {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (usage/resolve-connection (assoc-in api [:models :get-api-key-and-headers] (constantly {:ok false :error "no auth"})) ctx)))))

(deftest native-auth
  (let [{:keys [api]} (api-with-auth)
        claims {"email" "native@example.org"
                "https://api.openai.com/auth" {"chatgpt_account_id" "acct" "chatgpt_plan_type" "pro"}}
        token (str "header." (crypto/base64url (.getBytes (json/generate-string claims) "UTF-8")) ".sig")
        api (assoc-in api [:models :get-api-key-and-headers] (constantly {:ok true :api-key token}))
        native {:model {:provider :openai-codex :id "gpt"}}
        requests (atom [])]
    (with-redefs [http/request (fn [r] (swap! requests conj r) {:status 200 :body (json/generate-string payload)})]
      (is (= "native@example.org" (get-in (usage/fetch-status api native) [:account :email]))))
    (is (= "https://chatgpt.com/backend-api/wham/usage" (:url (first @requests))))
    (is (= "acct" (get-in (first @requests) [:headers "ChatGPT-Account-Id"])))
    (is (= "pi" (get-in (first @requests) [:headers "originator"])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (usage/resolve-connection (assoc-in api [:models :get-api-key-and-headers] (constantly {:ok true :api-key "bad"})) native)))))

(deftest native-reset-and-result-validation
  (let [{:keys [api state]} (api-with-auth)
        claims {"https://api.openai.com/auth" {"chatgpt_account_id" "acct"}}
        token (str "header." (crypto/base64url (.getBytes (json/generate-string claims) "UTF-8")) ".sig")
        api (assoc-in api [:models :get-api-key-and-headers] (constantly {:ok true :api-key token}))
        native {:model {:provider :openai-codex :id "gpt"}}
        requests (atom [])
        response (atom {})]
    (with-redefs [concurrent/spawn (fn [_] nil)
                  http/request (fn [r] (swap! requests conj r) {:status 200 :body (json/generate-string @response)})]
      (usage/init api)
      (let [reset-handler (get-in @state [:commands "codex-reset" :handler])]
        (reset-handler native "")
        (is (= "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits" (:url (first @requests))))
        (doseq [result [nil "reset" "already_redeemed" "nothing_to_reset" "no_credit"]]
          (reset! response (if result {:status result :rate_limit_windows_reset 2} {}))
          (reset-handler native "native-id")
          (let [r (last (filter #(= :post (:method %)) @requests))
                body (json/parse-string (:body r) true)]
            (is (= "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume" (:url r)))
            (is (= "native-id" (:credit_id body)))
            (is (re-matches #"[0-9a-f-]{36}" (:redeem_request_id body)))))
        (reset! response {:result "unexpected"})
        (reset! requests [])
        (reset-handler native "native-id")
        (is (= 1 (count @requests)))
        (is (= :error (last (last (:ui-calls @state)))))))))

(defn- command [state name] (get-in @state [:commands name :handler]))
(defn- notifications [state] (filter #(= :notify (first %)) (:ui-calls @state)))

(deftest commands-and-reset-credits
  (let [{:keys [api state]} (api-with-auth)
        requests (atom [])
        response (atom {})]
    (with-redefs [concurrent/spawn (fn [_] nil)
                  http/request (fn [r] (swap! requests conj r) {:status 200 :body (json/generate-string @response)})]
      (usage/init api)
      ((command state "codex-reset") ctx "")
      (is (= [:notify "No banked rate-limit reset credits." :info] (last (notifications state))))
      (reset! response {:credits [{:id "later" :status "banked" :granted_at "2026-01-01T12:30:00Z"}
                                  {:id "early" :status "banked" :granted_at "2026-01-01T12:30:00Z" :expires_at "2026-02-01T00:00:00Z"}]})
      ((command state "codex-reset") ctx "")
      (let [s (second (last (notifications state)))]
        (is (< (str/index-of s "early") (str/index-of s "later")))
        (is (str/includes? s "2026-01-01 12:30 UTC")))
      (reset! response {})
      ((command state "codex-reset") ctx " exact-id ")
      (let [r (first (filter #(= :post (:method %)) @requests))]
        (is (= "https://adapter.test/v1/codex/reset" (:url r)))
        (is (= {:credit_id "exact-id"} (json/parse-string (:body r) true))))
      (is (some #(str/includes? (second %) "activated (0 rate-limit windows reset)") (notifications state)))
      ((command state "codex-reset") ctx "not an id")
      (is (= :error (last (last (notifications state)))))
      (reset! response payload)
      ((command state "codex-usage") ctx "")
      (is (str/starts-with? (second (last (notifications state))) "ChatGPT Codex status"))
      (testing "failed explicit refresh reports only an error, never a success card"
        (reset! response {})
        (let [before (count (notifications state))]
          ((command state "codex-usage") ctx "")
          (is (= (inc before) (count (notifications state))))
          (is (= :error (last (last (notifications state))))))))))

(deftest stale-responses-and-shutdown
  (let [{:keys [api state]} (api-with-auth)
        jobs (atom [])]
    (with-redefs [concurrent/spawn (fn [f] (swap! jobs conj f))
                  usage/fetch-status (fn [_ _] (usage/parse-status payload))]
      (usage/init api)
      (let [start (first (get-in @state [:handlers :session-start]))
            select (first (get-in @state [:handlers :model-select]))]
        (start {} ctx)
        (select {} (assoc-in ctx [:model :id] "new"))
        ((second @jobs))
        (is (not-any? #(and (= :set-status (first %)) (some? (nth % 2))) (:ui-calls @state)))
        ((nth @jobs 2))
        (is (some #(and (= :set-status (first %)) (str/includes? (or (nth % 2) "") "76.5%/5h")) (:ui-calls @state)))
        (start {} ctx)
        (usage/shutdown api)
        (let [before (:ui-calls @state)]
          ((last @jobs))
          (is (= before (:ui-calls @state))))))))
