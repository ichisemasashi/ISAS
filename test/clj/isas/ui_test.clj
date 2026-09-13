(ns isas.ui-test
  (:require [clojure.test :refer [deftest is]]
            [isas.ui :as ui]))

(deftest helpers-test
  (is (= "&amp;&lt;&gt;&quot;" (ui/esc "&<>\"")))
  (is (= "" (ui/esc nil)))
  (is (= {} (ui/parse-query nil)))
  (is (= {} (ui/parse-query "")))
  (is (= {} (ui/parse-query "?")))
  (is (= {:a "1" :b "2"} (ui/parse-query "?a=1&b=2")))
  (is (= {:flag ""} (ui/parse-query "flag")))
  (doseq [[path page kind] [["/" :login "user"]
                            ["/reset/request" :reset-request "user"]
                            ["/reset" :reset "user"]
                            ["/home" :home "user"]
                            ["/invite" :invite "user"]
                            ["/password" :password "user"]
                            ["/fields" :fields "user"]
                            ["/map" :map "user"]
                            ["/map/place" :map-place "user"]
                            ["/admin" :login "admin"]
                            ["/admin/reset/request" :reset-request "admin"]
                            ["/admin/reset" :reset "admin"]
                            ["/admin/home" :home "admin"]
                            ["/admin/invite" :invite "admin"]
                            ["/admin/users" :users "admin"]
                            ["/admin/password" :password "admin"]
                            ["/nope" :unknown "user"]]]
    (let [r (ui/route-for path)]
      (is (= page (:page r)))
      (is (= kind (:kind r)))))
  (is (= "/" (ui/login-path "user")))
  (is (= "/admin" (ui/login-path "admin")))
  (is (= "/home" (ui/home-path "user")))
  (is (= "/admin/home" (ui/home-path "admin")))
  (is (true? (ui/needs-auth? :home)))
  (is (true? (ui/needs-auth? :fields)))
  (is (true? (ui/needs-auth? :map)))
  (is (true? (ui/needs-auth? :map-place)))
  (is (false? (ui/needs-auth? :login)))
  (is (= "メールアドレスまたはパスワードが違います" (ui/code-message "login_failed")))
  (is (= "この案内は使えません。もう一度やり直してください" (ui/code-message "reset_invalid")))
  (is (= "メールアドレスの形式ではありません" (ui/code-message "invite_invalid_email")))
  (is (re-find #"すでに" (ui/code-message "invite_duplicate_user")))
  (is (re-find #"管理者" (ui/code-message "invite_duplicate_admin")))
  (is (re-find #"確認用" (ui/code-message "password_mismatch")))
  (is (re-find #"8文字" (ui/code-message "password_too_short")))
  (is (re-find #"今のパスワード" (ui/code-message "password_wrong")))
  (is (re-find #"入っていません" (ui/code-message "unauthorized")))
  (is (re-find #"この入口" (ui/code-message "forbidden")))
  (is (re-find #"作業場所の範囲を決めて" (ui/code-message "place_unset")))
  (is (re-find #"作業場所の範囲が正しく" (ui/code-message "place_invalid")))
  (is (re-find #"閉じた形" (ui/code-message "shape_not_area")))
  (is (re-find #"下地の種類" (ui/code-message "basemap_kind")))
  (is (re-find #"その下地" (ui/code-message "basemap_missing")))
  (is (re-find #"その圃場" (ui/code-message "field_not_found")))
  (is (re-find #"分割は2枚" (ui/code-message "split_too_few")))
  (is (re-find #"合筆は2枚" (ui/code-message "merge_too_few")))
  (is (re-find #"残す圃場" (ui/code-message "merge_keep_missing")))
  (is (re-find #"区画として読めません" (ui/code-message "import_invalid")))
  (is (re-find #"通信" (ui/code-message "other"))))

(deftest render-all-pages-test
  (let [base (ui/init-state)]
    (doseq [page [:login :reset-request :reset :home :invite :password :users :unknown :fields :map :map-place]]
      (let [st (assoc base :page page :kind "user" :session {:email "a@b.c"}
                      :flash {:error? true :text "e"} :initial-password "pw"
                      :users [{:id 1 :email "x@y.z"}]
                      :fields [{:id 1 :name "北" :area_ha 1.2 :area_m2 12000}]
                      :basemaps [{:kind "aerial" :ready true} {:kind "standard" :ready false}])]
        (is (string? (ui/render st)))))
    (let [admin (assoc base :page :login :kind "admin")]
      (is (re-find #"管理者ログイン" (ui/render admin))))
    (is (re-find #"管理者として" (ui/render (assoc base :page :home :kind "admin" :session {:email "a"}))))
    (is (not (re-find #"href=\"/fields\"" (ui/render (assoc base :page :home :kind "admin" :session {:email "a"})))))
    (is (re-find #"href=\"/fields\"" (ui/render (assoc base :page :home :kind "user" :session {:email "a"}))))
    (is (re-find #"href=\"/map\"" (ui/render (assoc base :page :home :kind "user" :session {:email "a"}))))
    (is (re-find #"取り消す" (ui/render (assoc base :page :users :kind "admin" :users [{:id 2 :email "z@z.z"}]))))
    (is (re-find #"招待" (ui/render (assoc base :page :invite :kind "admin" :session {:email "a"}))))
    (is (re-find #"今のパスワード" (ui/render (assoc base :page :password :kind "admin" :session {:email "a"}))))
    (is (re-find #"ホーム" (ui/render (assoc base :page :invite :kind "user" :session {:email "a"} :initial-password nil))))
    (is (re-find #"圃場台帳" (ui/render (assoc base :page :fields :kind "user" :fields []))))
    (is (re-find #"<th>㎡</th>" (ui/render (assoc base :page :fields :kind "user"
                                                 :fields [{:id 1 :name "北" :area_ha 0 :area_m2 42}]))))
    (is (re-find #"42" (ui/render (assoc base :page :fields :kind "user"
                                         :fields [{:id 1 :name "北" :area_ha 0 :area_m2 42}]))))
    (is (re-find #"パソコンで開いてください" (ui/render (assoc base :page :map :kind "user" :narrow? true :session {:email "a"}))))
    (is (re-find #"この範囲を作業場所にする" (ui/render (assoc base :page :map-place :kind "user" :form {:west "1" :south "2" :east "3" :north "4"}))))
    (is (re-find #"先に作業場所の範囲を決めてください" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"ドラッグで移動" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"この範囲を作業場所にする" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"空中写真" (ui/render (assoc base :page :map :kind "user"
                                              :place {:west 1 :south 2 :east 3 :north 4}
                                              :basemaps [{:kind "aerial" :ready true}]))))
    (is (not (re-find #"data-kind=\"standard\"" (ui/render (assoc base :page :map :kind "user"
                                                                :place {:west 1 :south 2 :east 3 :north 4}
                                                                :basemaps [])))))))

(deftest handle-flow-test
  (let [s (ui/init-state)
        boot (ui/handle s [:boot {:path "/" :search ""}])]
    (is (= :session (ffirst (:fx boot))))
    (let [loaded (ui/handle (:state boot) [:session-loaded {:ok false :code "unauthorized"}])]
      (is (some? (ui/handle (:state loaded) [:path {:path "/home" :search ""}])))
      (is (= :nav (ffirst (:fx (ui/handle (assoc (:state loaded) :page :home) [:session-loaded {:ok false}])))))
      (let [ok (ui/handle s [:session-loaded {:ok true :email "a@b.c"}])]
        (is (= :nav (ffirst (:fx (ui/handle (assoc s :page :login :session {:email "a"}) [:session-loaded {:ok true :email "a"}])))))
        (let [users-boot (ui/handle (assoc s :page :users :kind "admin") [:session-loaded {:ok true :email "ad"}])]
          (is (= :api (ffirst (:fx users-boot)))))
        (is (map? (ui/handle s [:users-loaded {:ok true :users [{:id 1 :email "e"}]}])))
        (is (map? (ui/handle s [:users-loaded {}])))
        (is (= :nav (ffirst (:fx (ui/handle s [:login-result {:ok true :email "a"}])))))
        (is (re-find #"違います" (get-in (ui/handle s [:login-result {:ok false :code "login_failed"}]) [:state :flash :text])))
        (is (= :nav (ffirst (:fx (ui/handle s [:logout-result])))))
        (is (re-find #"案内" (get-in (ui/handle s [:reset-request-result]) [:state :flash :text])))
        (is (= :nav (ffirst (:fx (ui/handle s [:reset-complete-result {:ok true}])))))
        (is (true? (get-in (ui/handle s [:reset-complete-result {:ok false :code "reset_invalid"}]) [:state :flash :error?])))
        (is (seq (get-in (ui/handle s [:invite-result {:ok true :initial_password "x"}]) [:state :initial-password])))
        (is (true? (get-in (ui/handle s [:invite-result {:ok false :code "invite_duplicate_user"}]) [:state :flash :error?])))
        (is (false? (get-in (ui/handle s [:password-result {:ok true}]) [:state :flash :error?])))
        (is (true? (get-in (ui/handle s [:password-result {:ok false :code "password_wrong"}]) [:state :flash :error?])))
        (is (= :api (ffirst (:fx (ui/handle s [:revoke-result])))))
        (is (re-find #"通信" (get-in (ui/handle s [:api-error]) [:state :flash :text])))
        (is (= :html (ffirst (:fx (ui/handle s [:nope])))))
        (is (= :html (ffirst (:fx (ui/handle s :nope)))))
        (is (map? (ui/handle s [:path {:path "/invite" :search nil}])))
        (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "unknown" :form {}}])))))
        (doseq [act ["login" "logout" "reset-request" "reset-complete" "invite" "password" "revoke"]]
          (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "user") [:submit {:act act :form {:email "a" :token "t"}}])))))
          (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "admin") [:submit {:act act :form {:email "a"}}]))))))
        (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "user" :search "?token=fromq")
                                           [:submit {:act "reset-complete" :form {}}])))))
        (let [b (ui/handle s [:boot {:path "/reset" :search "?token=abc"}])]
          (is (= "abc" (get-in b [:state :form :token]))))
        (is (= :html (ffirst (:fx (ui/guarded (assoc s :page :login :session nil))))))
        (is (= :nav (ffirst (:fx (ui/guarded (assoc s :page :login :session {:email "a"}))))))
        (is (= :html (ffirst (:fx (ui/guarded (assoc s :page :reset :session nil))))))))))

(deftest farm-ui-test
  (is (nil? (ui/read-json-str nil)))
  (is (= {:a 1} (ui/read-json-str {:a 1})))
  (is (= [1] (ui/read-json-str [1])))
  (is (= {:a 1} (ui/read-json-str "{\"a\":1}")))
  (is (nil? (ui/read-json-str "{")))
  (let [s (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :map)
        fx-op (fn [st msg] (ffirst (:fx (ui/handle st msg))))]
    (is (= :api (fx-op (assoc s :page :map) [:session-loaded {:ok true :email "a"}])))
    (is (= :api (fx-op (assoc s :page :map-place) [:session-loaded {:ok true :email "a"}])))
    (is (= :api (fx-op (assoc s :page :fields) [:session-loaded {:ok true :email "a"}])))
    (is (= :html (fx-op (assoc s :page :map :narrow? true) [:session-loaded {:ok true :email "a"}])))
    (is (= :api (fx-op (assoc s :page :map) [:place-loaded {:ok false :code "place_unset"}])))
    (is (= :api (fx-op (assoc s :page :map) [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])))
    (is (= :api (fx-op (assoc s :page :map-place) [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])))
    (is (= :api (fx-op (assoc s :page :map) [:fields-loaded {:fields [{:id 1}]}])))
    (is (= :html (fx-op (assoc s :page :fields) [:fields-loaded {:fields []}])))
    (is (map? (ui/handle s [:fields-loaded {}])))
    (is (= :html (fx-op s [:basemaps-loaded {:ok true :basemaps [{:kind "aerial" :ready true}]}])))
    (is (map? (ui/handle s [:basemaps-loaded {}])))
    (is (= :nav (fx-op s [:place-save-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:place-save-result {:ok false :code "place_invalid"}]) [:state :flash :error?])))
    (is (= :api (fx-op s [:field-save-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:field-save-result {:ok false :code "shape_not_area"}]) [:state :flash :error?])))
    (is (= :api (fx-op s [:field-delete-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:field-delete-result {:ok false :code "field_not_found"}]) [:state :flash :error?])))
    (is (= :api (fx-op s [:basemap-upload-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:basemap-upload-result {:ok false :code "import_invalid"}]) [:state :flash :error?])))
    (is (= :api (fx-op (assoc s :page :map) [:narrow {:narrow? false}])))
    (is (= :html (fx-op (assoc (dissoc s :session) :page :login) [:narrow {:narrow? true}])))
    (is (= :api (fx-op (assoc s :page :home) [:path {:path "/map" :search ""}])))
    (is (= :api (fx-op (assoc s :page :home) [:path {:path "/fields" :search ""}])))
    (is (= :html (fx-op (assoc s :page :map) [:path {:path "/home" :search ""}])))
    (is (= :nav (fx-op (assoc (dissoc s :session) :page :home) [:path {:path "/map" :search ""}])))
    (let [b (ui/handle (ui/init-state) [:boot {:path "/map" :search "" :narrow? true}])]
      (is (true? (get-in b [:state :narrow?]))))
    (is (= :api (fx-op s [:submit {:act "save-place" :form {:west "1"}}])))
    (is (= :api (fx-op s [:submit {:act "create-field" :form {:name "n" :geojson "{\"type\":\"Polygon\"}"}}])))
    (is (= :api (fx-op s [:submit {:act "update-field" :form {:id "1" :name "n" :geojson "{\"type\":\"Polygon\"}"}}])))
    (is (= :api (fx-op s [:submit {:act "update-field" :form {:id "1"}}])))
    (is (= :api (fx-op s [:submit {:act "delete-field" :form {:id "1"}}])))
    (is (= :html (fx-op (assoc s :page :fields :narrow? true) [:session-loaded {:ok true :email "a"}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :map :kind "user") [:session-loaded {:ok false}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :map-place :kind "user") [:session-loaded {:ok false}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :fields :kind "user") [:session-loaded {:ok false}])))
    (is (= :api (fx-op s [:submit {:act "split-field" :form {:id "1"}}])))
    (is (= :api (fx-op s [:submit {:act "split-field" :form {:id "1" :polygons "[1]"}}])))
    (is (= :api (fx-op s [:submit {:act "merge-fields" :form {:keep_id "1"}}])))
    (is (= :api (fx-op s [:submit {:act "merge-fields" :form {:keep_id "1" :ids "[1,2]"}}])))
    (is (= :upload (fx-op s [:submit {:act "import-fields" :form {:file "x"}}])))
    (is (= :upload (fx-op s [:submit {:act "upload-basemap" :form {:kind "aerial" :file "x"}}])))))
