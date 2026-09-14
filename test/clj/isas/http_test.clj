(ns isas.http-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.geo :as geo]
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

(defn put-json
  ([app path body] (put-json app path body nil nil))
  ([app path body kind sid]
   (let [req (-> (mock/request :put path)
                 (mock/content-type "application/json")
                 (mock/body (json/write-str body)))]
     (app (if sid (as-user req kind sid) req)))))

(defn delete-path
  ([app path] (delete-path app path nil nil))
  ([app path kind sid]
   (let [req (mock/request :delete path)]
     (app (if sid (as-user req kind sid) req)))))

(def square
  {:type "Polygon"
   :coordinates [[[140.0 36.0]
                  [140.001 36.0]
                  [140.001 36.001]
                  [140.0 36.001]
                  [140.0 36.0]]]})

(def square-east
  {:type "Polygon"
   :coordinates [[[140.002 36.0]
                  [140.003 36.0]
                  [140.003 36.001]
                  [140.002 36.001]
                  [140.002 36.0]]]})

(deftest json-helpers-test
  (is (= 200 (:status (http/ok {}))))
  (is (= 401 (:status (http/fail "unauthorized"))))
  (is (= 403 (:status (http/fail "forbidden"))))
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
        (is (= 200 (:status (get-path app "/css/ol.css"))))
        (is (= 404 (:status (get-path app "/js/missing.js"))))
        (is (= 405 (:status (app (mock/request :post "/js/ok.js")))))
        (is (= 405 (:status (app (mock/request :post "/css/ol.css")))))
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

(deftest farm-api-test
  (tu/with-sys
    (fn [sys]
      (let [app (http/make-app sys)
            alogin (post-json app "/api/admin/login" {:email "admin@example.com" :password "ChangeMeAdmin1"})
            asid (cookie-value alogin "isas_admin")
            inv (post-json app "/api/admin/invite" {:email "farm@example.com"} "admin" asid)
            pw (:initial_password (parse inv))
            ulogin (post-json app "/api/user/login" {:email "farm@example.com" :password pw})
            usid (cookie-value ulogin "isas_user")
            inv2 (post-json app "/api/admin/invite" {:email "other@example.com"} "admin" asid)
            pw2 (:initial_password (parse inv2))
            ulogin2 (post-json app "/api/user/login" {:email "other@example.com" :password pw2})
            usid2 (cookie-value ulogin2 "isas_user")]
        (is (= 401 (:status (get-path app "/api/user/place"))))
        (is (= 403 (:status (get-path app "/api/user/place" "admin" asid))))
        (is (= "place_unset" (:code (parse (get-path app "/api/user/place" "user" usid)))))
        (is (= "place_unset" (:code (parse (post-json app "/api/user/place/preview" {} "user" usid)))))
        (is (= "place_unset" (:code (parse (post-json app "/api/user/emaff/import" {} "user" usid)))))
        (is (= "place_invalid" (:code (parse (put-json app "/api/user/place" {:west 1} "user" usid)))))
        (is (= "place_invalid" (:code (parse (app (as-user (-> (mock/request :put "/api/user/place")
                                                               (mock/content-type "application/json")
                                                               (mock/body "not-json"))
                                                          "user" usid))))))
        (is (true? (:ok (parse (put-json app "/api/user/place"
                                         {:west 139.0 :south 35.0 :east 141.0 :north 37.0}
                                         "user" usid)))))
        (is (true? (:ok (parse (get-path app "/api/user/place" "user" usid)))))
        (let [prev (parse (post-json app "/api/user/place/preview"
                                     {:west 139.0 :south 35.0 :east 141.0 :north 37.0}
                                     "user" usid))]
          (is (true? (:ok prev)))
          (is (= "gsi" (:source prev))))
        (is (= "place_invalid" (:code (parse (app (as-user (-> (mock/request :post "/api/user/place/preview")
                                                               (mock/content-type "application/json")
                                                               (mock/body "not-json"))
                                                          "user" usid))))))
        (with-redefs [isas.gsi/stitch-bbox (fn [_ _] nil)]
          (is (= "emaff_unavailable" (:code (parse (post-json app "/api/user/emaff/import" {} "user" usid))))))
        (is (= "place_invalid" (:code (parse (put-json app "/api/user/place/image" {:west 1} "user" usid)))))
        (is (= "place_invalid" (:code (parse (app (as-user (-> (mock/request :put "/api/user/place/image")
                                                               (mock/content-type "application/json")
                                                               (mock/body "not-json"))
                                                          "user" usid))))))
        (is (true? (:ok (parse (put-json app "/api/user/place/image"
                                         {:west 139.2 :south 35.2 :east 140.8 :north 36.8}
                                         "user" usid)))))
        (is (= 139.2 (:image_west (parse (get-path app "/api/user/place" "user" usid)))))
        (is (true? (:ok (parse (put-json app "/api/user/place/image" {:reset true} "user" usid)))))
        (is (nil? (:image_west (parse (get-path app "/api/user/place" "user" usid)))))
        (is (true? (:ok (parse (get-path app "/api/user/basemaps" "user" usid)))))
        (is (= "basemap_kind" (:code (parse (get-path app "/api/user/basemaps/nope" "user" usid)))))
        (is (= "basemap_missing" (:code (parse (get-path app "/api/user/basemaps/aerial" "user" usid)))))
        (let [tmp (java.io.File. (tu/temp-file "bmap" ".jpg" "fake-jpeg"))
              up (app (as-user (assoc (mock/request :put "/api/user/basemaps/aerial")
                                      :multipart-params {"file" {:filename "a.jpg"
                                                                 :content-type "image/jpeg"
                                                                 :tempfile tmp}})
                               "user" usid))
              bad (app (as-user (assoc (mock/request :put "/api/user/basemaps/nope")
                                       :multipart-params {"file" {:filename "a.jpg"
                                                                  :content-type "image/jpeg"
                                                                  :tempfile tmp}})
                                "user" usid))]
          (is (true? (:ok (parse up))))
          (is (= "basemap_kind" (:code (parse bad)))))
        (let [img (get-path app "/api/user/basemaps/aerial" "user" usid)]
          (is (= 200 (:status img)))
          (is (re-find #"image/jpeg" (str (get-in img [:headers "Content-Type"])))))
        (is (nil? (http/match-api :post "/api/user/basemaps/aerial")))
        (is (nil? (http/match-api :get "/api/user/fields/1")))
        (is (= [:basemap-put "aerial"] (http/match-api :put "/api/user/basemaps/aerial")))
        (is (= [:basemap-get "aerial"] (http/match-api :get "/api/user/basemaps/aerial")))
        (is (true? (:ok (parse (get-path app "/api/user/fields" "user" usid)))))
        (is (= "shape_not_area" (:code (parse (post-json app "/api/user/fields" {:name "x" :geojson {:type "Point" :coordinates [0 0]}} "user" usid)))))
        (is (= "shape_not_area" (:code (parse (app (as-user (-> (mock/request :post "/api/user/fields")
                                                               (mock/content-type "application/json")
                                                               (mock/body "not-json"))
                                                          "user" usid))))))
        (let [c1 (parse (post-json app "/api/user/fields" {:name "A" :geojson square} "user" usid))
              c2 (parse (post-json app "/api/user/fields" {:name "B" :geojson square-east} "user" usid))
              id (get-in c1 [:field :id])
              id2 (get-in c2 [:field :id])]
          (is (true? (:ok c1)))
          (is (= "field_not_found" (:code (parse (put-json app (str "/api/user/fields/" id) {:name "Z"} "user" usid2)))))
          (is (= "shape_not_area" (:code (parse (app (as-user (-> (mock/request :put (str "/api/user/fields/" id))
                                                                 (mock/content-type "application/json")
                                                                 (mock/body "not-json"))
                                                            "user" usid))))))
          (is (true? (:ok (parse (put-json app (str "/api/user/fields/" id) {:name "A2"} "user" usid)))))
          (is (= [:field-put (str id)] (http/match-api :put (str "/api/user/fields/" id))))
          (is (= [:field-delete (str id)] (http/match-api :delete (str "/api/user/fields/" id))))
          (is (= [:field-split (str id)] (http/match-api :post (str "/api/user/fields/" id "/split"))))
          (is (= [:field-complete (str id)] (http/match-api :post (str "/api/user/fields/" id "/complete"))))
          (is (= [:field-paints-delete (str id)] (http/match-api :delete (str "/api/user/fields/" id "/paints"))))
          (is (= [:paint-delete "9"] (http/match-api :delete "/api/user/paints/9")))
          (is (nil? (http/match-api :get (str "/api/user/fields/" id "/split"))))
          (is (nil? (http/match-api :put (str "/api/user/fields/" id "/complete"))))
          (is (nil? (http/match-api :get (str "/api/user/fields/" id "/paints"))))
          (is (nil? (http/match-api :get "/api/user/paints/9")))
          (is (= "split_too_few" (:code (parse (post-json app (str "/api/user/fields/" id "/split") {:polygons []} "user" usid)))))
          (is (= "merge_too_few" (:code (parse (post-json app "/api/user/fields/merge" {:keep_id id :ids [id]} "user" usid)))))
          (is (= "split_too_few" (:code (parse (app (as-user (-> (mock/request :post (str "/api/user/fields/" id "/split"))
                                                                (mock/content-type "application/json")
                                                                (mock/body "not-json"))
                                                           "user" usid))))))
          (is (= "merge_too_few" (:code (parse (app (as-user (-> (mock/request :post "/api/user/fields/merge")
                                                                (mock/content-type "application/json")
                                                                (mock/body "not-json"))
                                                           "user" usid))))))
          (let [sp (parse (post-json app (str "/api/user/fields/" id "/split")
                                     {:polygons [square square-east]} "user" usid))]
            (is (true? (:ok sp)))
            (is (= 2 (count (:fields sp)))))
          (let [listed (:fields (parse (get-path app "/api/user/fields" "user" usid)))
                a (:id (first listed))
                b (:id (second listed))
                mg (parse (post-json app "/api/user/fields/merge" {:keep_id a :ids [a b]} "user" usid))]
            (is (true? (:ok mg)))
            (is (true? (:ok (parse (delete-path app (str "/api/user/fields/" a) "user" usid))))))
          (is (= "import_invalid" (:code (parse (post-json app "/api/user/fields/import" {} "user" usid)))))
          (let [tmp (java.io.File. (tu/temp-file "imp" ".geojson" (geo/to-json square)))
                imp (app (as-user (assoc (mock/request :post "/api/user/fields/import")
                                         :params {:file {:filename "a.geojson" :tempfile tmp}})
                                  "user" usid))]
            (is (true? (:ok (parse imp)))))
          (let [tmp (java.io.File. (tu/temp-file "imp2" ".geojson" (geo/to-json square-east)))
                imp (app (as-user (assoc (mock/request :post "/api/user/fields/import")
                                         :multipart-params {:file {:filename "b.geojson" :tempfile tmp}})
                                  "user" usid))]
            (is (true? (:ok (parse imp)))))
          (is (some? (http/upload-of {:params {"file" :a}})))
          (is (some? (http/upload-of {:params {:file :b}})))
          (is (some? (http/upload-of {:multipart-params {"file" :c}})))
          (is (some? (http/upload-of {:multipart-params {:file :d}})))
          (is (nil? (http/upload-of {})))
          (is (= "field_not_found" (:code (parse (delete-path app "/api/user/fields/99999" "user" usid)))))
          (is (= 403 (:status (put-json app "/api/user/place" {:west 1 :south 2 :east 3 :north 4} "admin" asid)))))))))
