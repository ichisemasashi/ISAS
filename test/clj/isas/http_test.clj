(ns isas.http-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.http :as http]
            [isas.test-util :as tu]
            [ring.mock.request :as mock]))

(defn parse [resp]
  (let [b (:body resp)]
    (cond
      (map? b) b
      (string? b) (json/read-str b :key-fn keyword)
      (nil? b) nil
      :else (json/read-str (slurp b) :key-fn keyword))))

(defn- header-vals [resp name]
  (let [want (str/lower-case name)
        headers (:headers resp)]
    (->> headers
         (filter (fn [[k _]] (= want (str/lower-case (str k)))))
         (map val)
         (mapcat #(if (coll? %) % [%])))))

(defn cookie-value [resp name]
  (or (get-in resp [:cookies name :value])
      (some (fn [s]
              (second (re-find (re-pattern (str name "=([^;]+)")) (str s))))
            (header-vals resp "Set-Cookie"))))

(defn as-user [req kind sid]
  (mock/header req "cookie" (str (http/cookie-name kind) "=" sid)))

(defn post-json
  ([app path body] (post-json app path body nil nil))
  ([app path body kind sid]
   (let [req (-> (mock/request :post path)
                 (mock/content-type "application/json")
                 (mock/body (json/write-str body)))]
     (app (if sid (as-user req kind sid) req)))))

(defn get-path
  ([app path] (get-path app path nil nil))
  ([app path kind sid]
   (let [req (mock/request :get path)]
     (app (if sid (as-user req kind sid) req)))))

(deftest json-helpers-test
  (is (= 200 (:status (http/ok {}))))
  (is (= 401 (:status (http/fail "unauthorized"))))
  (is (true? (http/https? {:scheme :https})))
  (is (true? (http/https? {:scheme :http :headers {"x-forwarded-proto" "https"}})))
  (is (false? (http/https? {:scheme :http :headers {}})))
  (is (false? (http/https? {:headers {}})))
  (is (= "isas_admin" (http/cookie-name "admin")))
  (is (= "isas_user" (http/cookie-name "user")))
  (is (= {} (http/read-body {:body nil})))
  (is (= {:a 1} (http/read-body {:body {:a 1}})))
  (is (= {} (http/read-body {:body ""})))
  (is (= {:a 1} (http/read-body {:body "{\"a\":1}"})))
  (is (= {:a 1} (http/read-body {:body (java.io.ByteArrayInputStream. (.getBytes "{\"a\":1}" "UTF-8"))})))
  (is (= {} (http/read-body {:body (java.io.ByteArrayInputStream. (.getBytes "" "UTF-8"))}))))

(deftest spa-and-static-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)]
        (is (= 200 (:status (get-path app "/"))))
        (is (re-find #"ISAS" (let [b (:body (get-path app "/home"))]
                               (if (string? b) b (slurp b)))))
        (is (= 405 (:status (app (mock/request :post "/home")))))
        (is (= 200 (:status (get-path app "/js/ok.js"))))
        (is (= 200 (:status (get-path app "/js/note"))))
        (is (= 404 (:status (get-path app "/js/missing.js"))))
        (is (= 405 (:status (app (mock/request :post "/js/ok.js")))))
        (is (= 401 (:status (get-path app "/api/nope"))))
        (is (= 200 (:status (app {:request-method :get})))
            "SPA when uri is missing")
        (is (string? (http/index-html)))))))

(deftest user-and-admin-flow-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)
            bad (post-json app "/api/admin/login" {:email "admin@example.com" :password "no"})
            login (post-json app "/api/admin/login" {:email "admin@example.com" :password "ChangeMeAdmin1"})
            sid (cookie-value login "isas_admin")]
        (is (= "login_failed" (:code (parse bad))))
        (is (true? (:ok (parse login))))
        (is (seq sid))
        (is (true? (:ok (parse (get-path app "/api/admin/session" "admin" sid)))))
        (is (= 401 (:status (get-path app "/api/admin/session"))))
        (is (= 401 (:status (get-path app "/api/user/session" "admin" sid))))
        (let [inv (post-json app "/api/admin/invite" {:email "u@example.com"} "admin" sid)]
          (is (seq (:initial_password (parse inv))))
          (is (= "invite_duplicate_user" (:code (parse (post-json app "/api/admin/invite" {:email "u@example.com"} "admin" sid)))))
          (is (= "invite_duplicate_admin" (:code (parse (post-json app "/api/admin/invite" {:email "admin@example.com"} "admin" sid)))))
          (is (= "invite_invalid_email" (:code (parse (post-json app "/api/admin/invite" {:email "x"} "admin" sid)))))
          (let [users (parse (get-path app "/api/admin/users" "admin" sid))
                uid (get-in users [:users 0 :id])
                ulogin (post-json app "/api/user/login" {:email "u@example.com" :password (:initial_password (parse inv))})
                usid (cookie-value ulogin "isas_user")]
            (is (true? (:ok (parse ulogin))))
            (is (true? (:ok (parse (get-path app "/api/user/session" "user" usid)))))
            (let [uinv (post-json app "/api/user/invite" {:email "v@example.com"} "user" usid)]
              (is (seq (:initial_password (parse uinv)))))
            (is (true? (:ok (parse (post-json app "/api/user/password"
                                             {:current_password (:initial_password (parse inv))
                                              :password "userpass1"
                                              :password_confirm "userpass1"}
                                             "user" usid)))))
            (is (= "password_wrong" (:code (parse (post-json app "/api/user/password"
                                                            {:current_password "no"
                                                             :password "userpass2"
                                                             :password_confirm "userpass2"}
                                                            "user" usid)))))
            (is (true? (:ok (parse (post-json app "/api/admin/users/revoke" {:user_id uid} "admin" sid)))))
            (is (= "login_failed" (:code (parse (post-json app "/api/user/login" {:email "u@example.com" :password "userpass1"})))))
            (is (true? (:ok (parse (post-json app "/api/admin/users/revoke" {:user_id "999"} "admin" sid)))))
            (is (true? (:ok (parse (post-json app "/api/admin/users/revoke" {:user_id nil} "admin" sid)))))))
        (is (true? (:ok (parse (post-json app "/api/admin/password"
                                         {:current_password "ChangeMeAdmin1"
                                          :password "adminpass"
                                          :password_confirm "adminpass"}
                                         "admin" sid)))))
        (is (true? (:ok (parse (post-json app "/api/admin/logout" {} "admin" sid)))))
        (is (= 401 (:status (get-path app "/api/admin/session" "admin" sid))))
        (is (= 401 (:status (app (mock/request :post "/api/admin/logout")))))))))

(deftest reset-http-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)]
        (is (true? (:ok (parse (post-json app "/api/admin/password/reset/request" {:email "missing@x.x"})))))
        (is (true? (:ok (parse (post-json app "/api/admin/password/reset/request" {:email "admin@example.com"})))))
        (let [token (second (re-find #"token=([0-9a-f]+)" (:body (first @(:sent sys)))))]
          (is (= "reset_invalid" (:code (parse (post-json app "/api/admin/password/reset"
                                                         {:token "no" :password "abcdefgh" :password_confirm "abcdefgh"})))))
          (is (true? (:ok (parse (post-json app "/api/admin/password/reset"
                                           {:token token :password "newadmin1" :password_confirm "newadmin1"}))))))
        (let [login (post-json app "/api/admin/login" {:email "admin@example.com" :password "newadmin1"})
              sid (cookie-value login "isas_admin")]
          (post-json app "/api/admin/invite" {:email "mailuser@example.com"} "admin" sid)
          (is (true? (:ok (parse (post-json app "/api/user/password/reset/request" {:email "mailuser@example.com"})))))
          (let [token (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))]
            (is (true? (:ok (parse (post-json app "/api/user/password/reset"
                                             {:token token :password "mailpass1" :password_confirm "mailpass1"})))))))))))

(deftest unauthorized-and-bad-json-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)]
        (is (= 401 (:status (post-json app "/api/admin/invite" {:email "a@b.c"}))))
        (is (= 401 (:status (post-json app "/api/user/invite" {:email "a@b.c"}))))
        (is (= 401 (:status (post-json app "/api/admin/password" {:password "x"}))))
        (is (= 401 (:status (get-path app "/api/admin/users"))))
        (is (= 401 (:status (post-json app "/api/admin/users/revoke" {:user_id 1}))))
        (is (= "login_failed" (:code (parse (app (-> (mock/request :post "/api/admin/login")
                                                    (mock/content-type "application/json")
                                                    (mock/body "not-json")))))))
        (is (true? (:ok (parse (app (-> (mock/request :post "/api/admin/password/reset/request")
                                       (mock/content-type "application/json")
                                       (mock/body "not-json")))))))
        (is (= "reset_invalid" (:code (parse (app (-> (mock/request :post "/api/admin/password/reset")
                                                     (mock/content-type "application/json")
                                                     (mock/body "not-json")))))))
        (let [login (post-json app "/api/admin/login" {:email "admin@example.com" :password "ChangeMeAdmin1"})
              sid (cookie-value login "isas_admin")]
          (is (= "invite_invalid_email" (:code (parse (app (as-user (-> (mock/request :post "/api/admin/invite")
                                                                       (mock/content-type "application/json")
                                                                       (mock/body "not-json"))
                                                                  "admin" sid))))))
          (is (= "password_wrong" (:code (parse (app (as-user (-> (mock/request :post "/api/admin/password")
                                                                 (mock/content-type "application/json")
                                                                 (mock/body "not-json"))
                                                            "admin" sid))))))
          (is (true? (:ok (parse (app (as-user (-> (mock/request :post "/api/admin/users/revoke")
                                                  (mock/content-type "application/json")
                                                  (mock/body "not-json"))
                                             "admin" sid))))))
          (is (true? (http/https? {:scheme :http :headers {"x-forwarded-proto" "https"}})))
          (let [https-req (-> (mock/request :post "/api/admin/login")
                              (assoc :scheme :https)
                              (mock/content-type "application/json")
                              (mock/body (json/write-str {:email "admin@example.com" :password "ChangeMeAdmin1"})))
                https-resp (app https-req)]
            (is (or (true? (get-in https-resp [:cookies "isas_admin" :secure]))
                    (some #(re-find #"(?i)secure" (str %)) (header-vals https-resp "Set-Cookie"))))))))))

(deftest extra-api-codes-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)
            login (post-json app "/api/admin/login" {:email "admin@example.com" :password "ChangeMeAdmin1"})
            sid (cookie-value login "isas_admin")
            inv (post-json app "/api/admin/invite" {:email "pw@example.com"} "admin" sid)
            pw (:initial_password (parse inv))
            ulogin (post-json app "/api/user/login" {:email "pw@example.com" :password pw})
            usid (cookie-value ulogin "isas_user")]
        (is (= "password_mismatch" (:code (parse (post-json app "/api/admin/password"
                                                           {:current_password "ChangeMeAdmin1"
                                                            :password "abcdefgh"
                                                            :password_confirm "xxxxxxxx"}
                                                           "admin" sid)))))
        (is (= "password_too_short" (:code (parse (post-json app "/api/admin/password"
                                                            {:current_password "ChangeMeAdmin1"
                                                             :password "short"
                                                             :password_confirm "short"}
                                                            "admin" sid)))))
        (is (true? (:ok (parse (post-json app "/api/user/logout" {} "user" usid)))))
        (is (= 401 (:status (post-json app "/api/user/logout" {} "user" usid))))
        (is (true? (:ok (parse (post-json app "/api/admin/users/revoke" {:user_id "abc"} "admin" sid)))))
        (is (= 403 (:status (http/fail "x" 403))))
        (with-redefs [http/api-routes {[:get "/api/bogus-op"] [:bogus-op]}]
          (is (= "unauthorized" (:code (parse (http/dispatch-api sys {:request-method :get :uri "/api/bogus-op"}))))))))))
