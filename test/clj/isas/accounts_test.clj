(ns isas.accounts-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.test-util :as tu]
            [isas.time :as time])
  (:import [java.time Instant]))

(deftest bootstrap-test
  (tu/with-sys
    (fn [sys]
      (is (= :exists (:status (accounts/bootstrap-admin! (:ds sys) (:conf sys)))))))
  (let [ds (db/migrate! (db/datasource (db/sqlite-url (tu/temp-db-path))))]
    (binding [crypto/*cost* 4]
      (is (thrown-with-msg? Exception #"admin_email"
                            (accounts/bootstrap-admin! ds {:admin-email "" :admin-password ""})))
      (db/insert-user! ds {:email "admin@example.com" :password-hash "h"
                           :invited-by-kind "admin" :invited-by-id 1})
      (is (thrown-with-msg? Exception #"利用者"
                            (accounts/bootstrap-admin! ds {:admin-email "admin@example.com"
                                                           :admin-password "x"}))))))

(deftest login-logout-session-test
  (tu/with-sys
    (fn [sys]
      (is (= "login_failed" (:code (accounts/login sys "admin" "no@x.x" "x"))))
      (is (= "login_failed" (:code (accounts/login sys "admin" "admin@example.com" "wrong"))))
      (let [ok (accounts/login sys "admin" "ADMIN@example.com" "ChangeMeAdmin1")]
        (is (true? (:ok ok)))
        (is (some? (accounts/current-session sys "admin" (:session-id ok))))
        (is (nil? (accounts/current-session sys "user" (:session-id ok))))
        (is (nil? (accounts/current-session sys "admin" "missing")))
        (db/insert-session! (:ds sys) {:id "expired" :kind "admin" :account-id 1 :expires-at "2000-01-01T00:00:00Z"})
        (is (nil? (accounts/current-session sys "admin" "expired")))
        (db/insert-session! (:ds sys) {:id "ghost" :kind "admin" :account-id 999 :expires-at (time/plus-days 1)})
        (is (nil? (accounts/current-session sys "admin" "ghost")))
        (is (true? (:ok (accounts/logout sys "admin" (:session-id ok)))))
        (is (nil? (accounts/current-session sys "admin" (:session-id ok))))
        (is (= "unauthorized" (:code (accounts/logout sys "admin" (:session-id ok))))))
      (let [inv (accounts/invite sys "admin" 1 "helper@example.com")]
        (is (true? (:ok inv)))
        (let [uok (accounts/login sys "user" "helper@example.com" (:initial_password inv))]
          (is (true? (:ok uok)))
          (accounts/revoke-user sys (:id (db/find-user-by-email (:ds sys) "helper@example.com")))
          (is (nil? (accounts/current-session sys "user" (:session-id uok))))
          (is (= "login_failed" (:code (accounts/login sys "user" "helper@example.com" (:initial_password inv))))))))))

(deftest invite-rules-test
  (tu/with-sys
    (fn [sys]
      (is (= "invite_invalid_email" (:code (accounts/invite sys "admin" 1 "bad"))))
      (is (= "invite_duplicate_admin" (:code (accounts/invite sys "admin" 1 "admin@example.com"))))
      (let [a (accounts/invite sys "admin" 1 "a@example.com")]
        (is (true? (:ok a)))
        (is (= "invite_duplicate_user" (:code (accounts/invite sys "admin" 1 "a@example.com"))))
        (let [uid (:id (db/find-user-by-email (:ds sys) "a@example.com"))]
          (accounts/revoke-user sys uid)
          (accounts/revoke-user sys uid)
          (accounts/revoke-user sys 99999)
          (let [b (accounts/invite sys "user" uid "a@example.com")]
            (is (true? (:ok b)))
            (is (not= (:initial_password a) (:initial_password b)))
            (is (true? (:ok (accounts/login sys "user" "a@example.com" (:initial_password b)))))))))))

(deftest password-change-test
  (tu/with-sys
    (fn [sys]
      (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
        (is (= "password_mismatch" (:code (accounts/change-password sys "admin" admin "n" "c" "x"))))
        (is (= "password_too_short" (:code (accounts/change-password sys "admin" admin "short" "ChangeMeAdmin1" "short"))))
        (is (= "password_too_short" (:code (accounts/change-password sys "admin" admin nil "ChangeMeAdmin1" nil))))
        (is (= "password_wrong" (:code (accounts/change-password sys "admin" admin "newpass12" "nope" "newpass12"))))
        (is (true? (:ok (accounts/change-password sys "admin" admin "newpass12" "ChangeMeAdmin1" "newpass12"))))
        (is (true? (:ok (accounts/login sys "admin" "admin@example.com" "newpass12")))))
      (let [inv (accounts/invite sys "admin" 1 "p@example.com")
            user (db/find-user-by-email (:ds sys) "p@example.com")]
        (is (true? (:ok (accounts/change-password sys "user" user "userpass1" (:initial_password inv) "userpass1"))))))))

(deftest reset-flow-test
  (tu/with-sys
    (fn [sys]
      (is (true? (:ok (accounts/request-reset sys "admin" "missing@example.com"))))
      (is (empty? @(:sent sys)))
      (is (true? (:ok (accounts/request-reset sys "admin" "admin@example.com"))))
      (is (= 1 (count @(:sent sys))))
      (let [token (second (re-find #"token=([0-9a-f]+)" (:body (first @(:sent sys)))))]
        (is (= "password_mismatch" (:code (accounts/complete-reset sys "admin" token "abcdefgh" "xxxxxxxx"))))
        (is (= "password_too_short" (:code (accounts/complete-reset sys "admin" token nil nil))))
        (is (= "password_too_short" (:code (accounts/complete-reset sys "admin" token "short" "short"))))
        (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" "deadbeef" "abcdefgh" "abcdefgh"))))
        (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" nil "abcdefgh" "abcdefgh"))))
        (is (true? (:ok (accounts/complete-reset sys "admin" token "resetpass" "resetpass"))))
        (is (true? (:ok (accounts/login sys "admin" "admin@example.com" "resetpass"))))
        (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" token "resetpass" "resetpass")))))
      (accounts/invite sys "admin" 1 "r@example.com")
      (accounts/request-reset sys "user" "r@example.com")
      (let [token (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))
            uid (:id (db/find-user-by-email (:ds sys) "r@example.com"))]
        (is (true? (:ok (accounts/complete-reset sys "user" token "resetuser" "resetuser"))))
        (accounts/request-reset sys "user" "r@example.com")
        (let [tok2 (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))
              n (count @(:sent sys))]
          (accounts/revoke-user sys uid)
          (accounts/request-reset sys "user" "r@example.com")
          (is (= n (count @(:sent sys))))
          (is (= "reset_invalid" (:code (accounts/complete-reset sys "user" tok2 "abcdefgh" "abcdefgh"))))))))
  (tu/with-sys
    (fn [sys]
      (binding [crypto/*cost* 4]
        (db/insert-reset-token! (:ds sys) {:kind "admin"
                                           :account-id 999
                                           :token-hash (crypto/hash-secret "orphan-token-xx")
                                           :expires-at (time/plus-hours 1)})
        (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" "orphan-token-xx" "abcdefgh" "abcdefgh"))))
        (db/insert-reset-token! (:ds sys) {:kind "user"
                                           :account-id 999
                                           :token-hash (crypto/hash-secret "user-orphan-token")
                                           :expires-at (time/plus-hours 1)})
        (is (= "reset_invalid" (:code (accounts/complete-reset sys "user" "user-orphan-token" "abcdefgh" "abcdefgh")))))))
  (let [fixed (Instant/parse "2026-09-12T00:00:00Z")]
    (tu/with-sys {:now fixed}
      (fn [sys]
        (binding [crypto/*cost* 4]
          (db/insert-reset-token! (:ds sys) {:kind "admin"
                                             :account-id 1
                                             :token-hash (crypto/hash-secret "old-token-xxxx")
                                             :expires-at "2026-09-11T00:00:00Z"})
          (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" "old-token-xxxx" "abcdefgh" "abcdefgh")))))))))
