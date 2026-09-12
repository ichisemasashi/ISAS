(ns isas.db-test
  (:require [clojure.test :refer [deftest is]]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.test-util :as tu]
            [isas.time :as time]))

(deftest migrate-and-crud-test
  (binding [crypto/*cost* 4]
    (let [ds (:ds (tu/test-system))]
      (is (pos? (db/count-admins ds)))
      (is (some? (db/find-admin-by-email ds "admin@example.com")))
      (is (nil? (db/find-admin-by-email ds "no@x.x")))
      (let [admin (db/find-admin-by-email ds "admin@example.com")]
        (is (= (:email admin) (:email (db/find-admin-by-id ds (:id admin)))))
        (db/update-admin-password! ds (:id admin) "new-hash")
        (is (= "new-hash" (:password_hash (db/find-admin-by-id ds (:id admin))))))
      (let [u (db/insert-user! ds {:email "u@example.com"
                                   :password-hash "h"
                                   :invited-by-kind "admin"
                                   :invited-by-id 1})]
        (is (= "u@example.com" (:email (db/find-user-by-email ds "u@example.com"))))
        (is (= "u@example.com" (:email (db/find-user-by-id ds (:id u)))))
        (is (= 1 (count (db/list-active-users ds))))
        (db/update-user-password! ds (:id u) "h2")
        (is (= "h2" (:password_hash (db/find-user-by-id ds (:id u)))))
        (db/revoke-user! ds (:id u))
        (is (seq (:revoked_at (db/find-user-by-id ds (:id u)))))
        (is (empty? (db/list-active-users ds)))
        (db/reinvite-user! ds (:id u) "h3" "user" 9)
        (is (nil? (:revoked_at (db/find-user-by-id ds (:id u)))))
        (is (= "user" (:invited_by_kind (db/find-user-by-id ds (:id u))))))
      (let [sid "abc"]
        (db/insert-session! ds {:id sid :kind "user" :account-id 1 :expires-at (time/plus-days 1)})
        (is (nil? (:revoked_at (db/find-session ds sid))))
        (db/revoke-session! ds sid)
        (is (seq (:revoked_at (db/find-session ds sid))))
        (db/insert-session! ds {:id "s2" :kind "user" :account-id 1 :expires-at (time/plus-days 1)})
        (db/revoke-user-sessions! ds 1)
        (is (seq (:revoked_at (db/find-session ds "s2")))))
      (let [tok (db/insert-reset-token! ds {:kind "user" :account-id 1 :token-hash "th" :expires-at (time/plus-hours 1)})]
        (is (= 1 (count (db/open-reset-tokens ds "user"))))
        (db/invalidate-reset-tokens! ds "user" 1)
        (is (empty? (db/open-reset-tokens ds "user")))
        (db/insert-reset-token! ds {:kind "user" :account-id 1 :token-hash "th2" :expires-at (time/plus-hours 1)})
        (let [id (:id (first (db/open-reset-tokens ds "user")))]
          (db/mark-token-used! ds id)
          (is (empty? (db/open-reset-tokens ds "user")))))
      (is (re-find #"jdbc:sqlite:" (db/sqlite-url "data/x.sqlite")))
      (let [u (db/find-user-by-email ds "u@example.com")
            p (db/upsert-place! ds (:id u) {:west 139.0 :south 35.0 :east 140.0 :north 36.0})]
        (is (= 139.0 (:west (db/find-place ds (:id u)))))
        (is (some? p))
        (db/upsert-place! ds (:id u) {:west 138.0 :south 34.0 :east 139.0 :north 35.0})
        (is (= 138.0 (:west (db/find-place ds (:id u)))))
        (let [bm (db/upsert-basemap! ds {:user-id (:id u) :kind "aerial" :content-type "image/jpeg" :body-ref "1/aerial.jpg"})]
          (is (= "aerial" (:kind (db/find-basemap ds (:id u) "aerial"))))
          (is (= 1 (count (db/list-basemaps ds (:id u)))))
          (db/upsert-basemap! ds {:user-id (:id u) :kind "aerial" :content-type "image/png" :body-ref "1/aerial.png"})
          (is (= "image/png" (:content_type (db/find-basemap ds (:id u) "aerial"))))
          (is (some? bm))
          (db/delete-basemaps! ds (:id u))
          (is (empty? (db/list-basemaps ds (:id u)))))
        (let [f (db/insert-field! ds {:user-id (:id u) :name "A" :geojson "{\"type\":\"Polygon\"}"})]
          (is (= "A" (:name (db/find-field ds (:id u) (:id f)))))
          (is (= ["A"] (db/field-names ds (:id u))))
          (is (= 1 (count (db/list-fields ds (:id u)))))
          (db/update-field! ds (:id f) {:name "B" :geojson "{}"})
          (is (= "B" (:name (db/find-field ds (:id u) (:id f)))))
          (db/delete-field! ds (:id f))
          (is (nil? (db/find-field ds (:id u) (:id f)))))))))
