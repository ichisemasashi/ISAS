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
                            ["/gantt" :gantt "user"]
                            ["/admin" :login "admin"]
                            ["/admin/reset/request" :reset-request "admin"]
                            ["/admin/reset" :reset "admin"]
                            ["/admin/home" :home "admin"]
                            ["/admin/invite" :invite "admin"]
                            ["/admin/users" :users "admin"]
                            ["/admin/password" :password "admin"]
                            ["/nope" :unknown "user"]
                            ["/home/" :home "user"]
                            ["/fields/" :fields "user"]
                            ["/map/" :map "user"]
                            ["/admin/fields" :fields "user"]
                            ["/admin/map" :map "user"]
                            ["/admin/map/place/" :map-place "user"]
                            ["/admin/home/" :home "admin"]]]
    (let [r (ui/route-for path)]
      (is (= page (:page r)))
      (is (= kind (:kind r)))))
  (is (= "/" (ui/normalize-path "")))
  (is (= "/" (ui/normalize-path nil)))
  (is (= "/home" (ui/normalize-path "/home/")))
  (is (= "/" (ui/login-path "user")))
  (is (= "/admin" (ui/login-path "admin")))
  (is (= "/home" (ui/home-path "user")))
  (is (= "/admin/home" (ui/home-path "admin")))
  (is (true? (ui/needs-auth? :home)))
  (is (true? (ui/needs-auth? :fields)))
  (is (true? (ui/needs-auth? :map)))
  (is (true? (ui/needs-auth? :map-place)))
  (is (true? (ui/needs-auth? :gantt)))
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
  (is (re-find #"自動取込ができませんでした" (ui/code-message "emaff_unavailable")))
  (is (re-find #"一部だけ自動取込" (ui/code-message "emaff_partial")))
  (is (re-find #"取り込んでいます" (ui/code-message "emaff_busy")))
  (is (re-find #"自動取込ができませんでした" (ui/code-message "emaff_empty")))
  (is (re-find #"作業場所の範囲が正しく" (ui/code-message "place_invalid")))
  (is (re-find #"閉じた形" (ui/code-message "shape_not_area")))
  (is (re-find #"下地の種類" (ui/code-message "basemap_kind")))
  (is (re-find #"その下地" (ui/code-message "basemap_missing")))
  (is (re-find #"その圃場" (ui/code-message "field_not_found")))
  (is (re-find #"分割は2枚" (ui/code-message "split_too_few")))
  (is (re-find #"合筆は2枚" (ui/code-message "merge_too_few")))
  (is (re-find #"残す圃場" (ui/code-message "merge_keep_missing")))
  (is (re-find #"区画として読めません" (ui/code-message "import_invalid")))
  (is (re-find #"作業名を入れてから" (ui/code-message "work_name_required")))
  (is (re-find #"100文字" (ui/code-message "work_name_too_long")))
  (is (re-find #"分割できません" (ui/code-message "field_has_paint")))
  (is (re-find #"その塗り" (ui/code-message "paint_not_found")))
  (is (re-find #"内側に塗れる" (ui/code-message "paint_empty")))
  (is (re-find #"%E7%94%B0" (ui/encode-q "田植え")))
  (is (re-find #"work_name=" (ui/paints-query "田植え")))
  (is (re-find #"/paints\?work_name=" (ui/field-paints-query 1 "田植え")))
  (is (re-find #"合筆できません" (ui/paint-block-text "merge")))
  (is (re-find #"分割できません" (ui/paint-block-text "split")))
  (is (re-find #"通信" (ui/code-message "other")))
  (is (= {:west 1.0 :south 2.0 :east 3.0 :north 4.0}
         (ui/shift-bbox {:west 0.0 :south 2.0 :east 2.0 :north 4.0} 1.0 0.0)))
  (let [s (ui/scale-bbox {:west 0.0 :south 0.0 :east 2.0 :north 2.0} 2.0)]
    (is (= -1.0 (:west s)))
    (is (= 3.0 (:east s))))
  (is (= {:west 0.0 :south 0.0 :east 2.0 :north 2.0}
         (ui/scale-bbox {:west 0.0 :south 0.0 :east 2.0 :north 2.0} 0)))
  (is (= {:west 0.0 :south 0.0 :east 2.0 :north 2.0}
         (ui/scale-bbox {:west 0.0 :south 0.0 :east 2.0 :north 2.0} nil)))
  (is (nil? (ui/image-bbox nil)))
  (is (= {:west 1 :south 2 :east 3 :north 4}
         (ui/image-bbox {:west 1 :south 2 :east 3 :north 4})))
  (is (= {:west 9 :south 8 :east 7 :north 6}
         (ui/image-bbox {:west 1 :south 2 :east 3 :north 4
                         :image_west 9 :image_south 8 :image_east 7 :image_north 6}))))

(deftest render-all-pages-test
  (let [base (ui/init-state)]
    (doseq [page [:login :reset-request :reset :home :invite :password :users :unknown :fields :map :map-place :gantt]]
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
    (is (re-find #"下地を西へ" (ui/render (assoc base :page :map :session {:email "a"}
                                               :map-mode "basemap"
                                               :place {:west 1 :south 2 :east 3 :north 4}
                                               :basemaps [{:kind "aerial" :ready true}]))))
    (is (re-find #"衛星|data-select=\"basemap-kind\""
                 (ui/render (assoc base :page :map :session {:email "a"}
                                   :place {:west 1 :south 2 :east 3 :north 4}
                                   :basemap-kind "satellite"
                                   :basemaps [{:kind "satellite" :ready true}
                                              {:kind "aerial" :ready true}]))))
    (is (re-find #"自動で取り込む" (ui/render (assoc base :page :map :session {:email "a"}
                                                  :map-mode "basemap"
                                                  :place {:west 1 :south 2 :east 3 :north 4}
                                                  :basemaps []))))
    (is (re-find #"確認用の空中写真" (ui/render (assoc base :page :map-place :kind "user"
                                                     :place-preview "aerial"))))
    (is (re-find #"いまの作業場所を変えられます"
                 (ui/render (assoc base :page :map-place :kind "user"
                                   :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"取り込んでいます"
                 (ui/render (assoc base :page :map-place :kind "user" :place-busy true))))
    (is (not (re-find #"この範囲を作業場所にする"
                      (ui/render (assoc base :page :map-place :kind "user" :place-busy true)))))
    (is (not (re-find #"下地を西へ" (ui/render (assoc base :page :map :session {:email "a"}
                                                    :place {:west 1 :south 2 :east 3 :north 4}
                                                    :basemaps [])))))
    (is (re-find #"下地の位置を保存" (ui/render (assoc base :page :map :session {:email "a"}
                                                     :map-mode "basemap"
                                                     :place {}
                                                     :basemaps [{:kind "aerial" :ready true}]))))
    (is (re-find #"取り消す" (ui/render (assoc base :page :users :kind "admin" :users [{:id 2 :email "z@z.z"}]))))
    (is (re-find #"招待" (ui/render (assoc base :page :invite :kind "admin" :session {:email "a"}))))
    (is (re-find #"今のパスワード" (ui/render (assoc base :page :password :kind "admin" :session {:email "a"}))))
    (is (re-find #"ホーム" (ui/render (assoc base :page :invite :kind "user" :session {:email "a"} :initial-password nil))))
    (is (re-find #"圃場台帳" (ui/render (assoc base :page :fields :kind "user" :fields []))))
    (is (re-find #"名前を保存" (ui/render (assoc base :page :fields :kind "user"
                                                :fields [{:id 1 :name "北" :area_ha 0.1 :area_m2 1000}]))))
    (is (re-find #"<th>㎡</th>" (ui/render (assoc base :page :fields :kind "user"
                                                 :fields [{:id 1 :name "北" :area_ha 0 :area_m2 42}]))))
    (is (re-find #"42" (ui/render (assoc base :page :fields :kind "user"
                                         :fields [{:id 1 :name "北" :area_ha 0 :area_m2 42}]))))
    (is (re-find #"パソコンで開いてください" (ui/render (assoc base :page :map :kind "user" :narrow? true :session {:email "a"}))))
    (is (re-find #"この範囲を作業場所にする" (ui/render (assoc base :page :map-place :kind "user" :form {:west "1" :south "2" :east "3" :north "4"}))))
    (is (re-find #"空中写真で最終確認" (ui/render (assoc base :page :map-place :kind "user"))))
    (is (re-find #"国土地理院" (ui/render (assoc base :page :map-place :kind "user"))))
    (is (re-find #"data-preview=\"aerial\"" (ui/render (assoc base :page :map-place :kind "user" :place-preview "aerial"))))
    (is (re-find #"地理院地図に戻って範囲を直す"
                 (ui/render (assoc base :page :map-place :kind "user" :place-preview "aerial"
                                   :form {:west "140" :south "35" :east "141" :north "36"}))))
    (is (re-find #"data-west=\"140\"" (ui/render (assoc base :page :map-place :kind "user"
                                                        :form {:west "140" :south "35" :east "141" :north "36"}))))
    (is (not (re-find #"空中写真で最終確認"
                      (ui/render (assoc base :page :map-place :kind "user" :place-preview "aerial")))))
    (is (re-find #"先に作業場所の範囲を決めてください" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"地理院地図を動かして" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"この範囲を作業場所にする" (ui/render (assoc base :page :map :kind "user"))))
    (is (re-find #"空中写真" (ui/render (assoc base :page :map :kind "user"
                                              :place {:west 1 :south 2 :east 3 :north 4}
                                              :basemaps [{:kind "aerial" :ready true}]))))
    (is (not (re-find #"data-kind=\"standard\"" (ui/render (assoc base :page :map :kind "user"
                                                                :place {:west 1 :south 2 :east 3 :north 4}
                                                                :basemaps [])))))
    (is (re-find #"塗りを確定する" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                                     :map-mode "paint"
                                                     :form {:work_name "田植え"}
                                                     :place {:west 1 :south 2 :east 3 :north 4}
                                                     :fields [{:id 1 :name "北"}]
                                                     :work-names ["田植え"]))))
    (is (re-find #"option value=\"田植え\"" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                                              :map-mode "paint"
                                                              :form {:work_name "田植え"}
                                                              :place {:west 1 :south 2 :east 3 :north 4}
                                                              :fields [{:id 1 :name "北"}]
                                                              :work-names ["田植え"]))))
    (is (not (re-find #"塗りを確定する" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                                          :place {:west 1 :south 2 :east 3 :north 4}
                                                          :fields [])))))
    (is (re-find #"手描き" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                            :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (not (re-find #"圃場を保存" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                                      :place {:west 1 :south 2 :east 3 :north 4})))))
    (is (re-find #"圃場を保存" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                                :map-mode "draw"
                                                :place {:west 1 :south 2 :east 3 :north 4}))))
    (let [r (ui/handle (assoc base :page :map :session {:email "a"}
                              :place {:west 1 :south 2 :east 3 :north 4}
                              :map-mode "draw")
                       [:submit {:act "set-map-mode" :form {:mode "cancel"}}])]
      (is (= "browse" (get-in r [:state :map-mode]))))
    (let [r (ui/handle (assoc base :page :map :session {:email "a"}
                              :form {:work_name "田植え"} :map-mode "paint")
                       [:submit {:act "set-map-mode" :form {:mode "basemap"}}])]
      (is (= "basemap" (get-in r [:state :map-mode])))
      (is (= "paint" (get-in r [:state :map-mode-parent]))))
    (let [r (ui/handle (assoc base :page :map :session {:email "a"}
                              :map-mode "basemap" :map-mode-parent "paint"
                              :form {:work_name "田植え"})
                       [:submit {:act "set-map-mode" :form {:mode "cancel"}}])]
      (is (= "paint" (get-in r [:state :map-mode]))))
    (is (= "paint" (ui/map-mode {:form {:work_name "田植え"}})))
    (is (re-find #"やめる" (ui/render (assoc base :page :map :kind "user" :session {:email "a"}
                                            :map-mode "paint"
                                            :place {:west 1 :south 2 :east 3 :north 4}))))
    (doseq [mode ["edit" "split" "merge" "import" "browse" "paint" "draw"]]
      (let [r (ui/handle (assoc base :page :map :session {:email "a"}
                                :place {:west 1 :south 2 :east 3 :north 4})
                         [:submit {:act "set-map-mode" :form {:mode mode}}])]
        (is (= mode (get-in r [:state :map-mode])))
        (is (string? (ui/render (get r :state))))))
    (is (= :html (ffirst (:fx (ui/handle (assoc base :page :map :session {:email "a"}
                                               :place {:west 1 :south 2 :east 3 :north 4})
                                        [:submit {:act "set-map-mode" :form {:mode "nope"}}])))))
    (is (re-find #"形と名前を保存" (ui/render (assoc base :page :map :session {:email "a"}
                                                    :map-mode "edit"
                                                    :form {:id "1" :name "北"}
                                                    :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"分割を保存" (ui/render (assoc base :page :map :session {:email "a"}
                                                :map-mode "split"
                                                :form {:id "1"}
                                                :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"合筆する" (ui/render (assoc base :page :map :session {:email "a"}
                                              :map-mode "merge"
                                              :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"取り込む" (ui/render (assoc base :page :map :session {:email "a"}
                                              :map-mode "import"
                                              :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"塗りを確定する" (ui/render (assoc base :page :map :session {:email "a"}
                                                     :map-mode "paint"
                                                     :form {:work_name "田植え" :field_id "1" :paint-id "2" :paint-geojson "{}"}
                                                     :place {:west 1 :south 2 :east 3 :north 4}
                                                     :fields [{:id 1 :name "北"}]
                                                     :work-names ["田植え"]))))
    (is (re-find #"name=\"id\" value=\"3\"" (ui/render (assoc base :page :map :session {:email "a"}
                                                             :map-mode "paint"
                                                             :form {:work_name "田植え" :id "3"}
                                                             :place {:west 1 :south 2 :east 3 :north 4}
                                                             :fields [{:id 3 :name "北"}]
                                                             :work-names ["田植え"]))))
    (is (re-find #"name=\"id\" value=\"9\"" (ui/render (assoc base :page :map :session {:email "a"}
                                                             :map-mode "split"
                                                             :form {:split-id "9"}
                                                             :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"name=\"id\" value=\"8\"" (ui/render (assoc base :page :map :session {:email "a"}
                                                             :map-mode "split"
                                                             :form {:id "8"}
                                                             :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"name=\"id\" value=\"7\"" (ui/render (assoc base :page :map :session {:email "a"}
                                                             :map-mode "split"
                                                             :form {:field_id "7"}
                                                             :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"手描き" (ui/render (assoc base :page :map :session {:email "a"}
                                            :map-mode "weird"
                                            :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (re-find #"いま必要な操作" (ui/render (assoc base :page :map :session {:email "a"}
                                                    :map-mode "weird"
                                                    :place {:west 1 :south 2 :east 3 :north 4}))))
    (is (= :html (ffirst (:fx (ui/handle (assoc base :page :map :session {:email "a"}
                                               :place {:west 1 :south 2 :east 3 :north 4})
                                        [:submit {:act "set-map-mode" :form {}}])))))))

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
        (is (= :restore-guest-lang (ffirst (:fx (ui/handle s [:logout-result])))))
        (is (= :nav (first (second (:fx (ui/handle s [:logout-result]))))))
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
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :form {})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :form nil)
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "" :south "8" :east "7" :north "6"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "" :east "7" :north "6"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "8" :east "" :north "6"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "8" :east "7" :north ""})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "8" :east "7"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "8"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (= "9" (get-in (ui/handle (assoc s :page :map-place
                                         :form {:west "9" :south "8" :east "7" :north "6"})
                                  [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                       [:state :form :west])))
    (is (nil? (get-in (ui/handle (assoc s :page :map :form nil)
                                 [:place-loaded {:ok true :west 1 :south 2 :east 3 :north 4}])
                      [:state :form :west])))
    (is (nil? (get-in (ui/handle (assoc s :page :map-place :form nil)
                                 [:place-loaded {:ok false :code "place_unset"}])
                      [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :form {:preview-note "x"})
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:west 1 :south 2 :east 3 :north 4}}])
                       [:state :form :west])))
    (is (nil? (get-in (ui/handle (assoc s :page :map-place :form {:preview-note "x"})
                                 [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                         :bbox {:west 1 :south 2 :east 3 :north 4}}])
                      [:state :form :preview-note])))
    (is (= :api (fx-op (assoc s :page :map) [:fields-loaded {:fields [{:id 1}]}])))
    (is (= :html (fx-op (assoc s :page :fields) [:fields-loaded {:fields []}])))
    (is (map? (ui/handle s [:fields-loaded {}])))
    (is (= :api (fx-op s [:basemaps-loaded {:ok true :basemaps [{:kind "aerial" :ready true}]}])))
    (is (= :html (fx-op (assoc s :page :fields) [:basemaps-loaded {:ok true :basemaps []}])))
    (is (= :html (fx-op (assoc s :page :map-place) [:work-names-loaded {:work_names []}])))
    (is (map? (ui/handle s [:basemaps-loaded {}])))
    (is (= :html (fx-op s [:place-save-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:place-save-result {:ok true}]) [:state :place-busy])))
    (is (true? (get-in (ui/handle s [:place-save-result {:ok false :code "place_invalid"}]) [:state :flash :error?])))
    (is (nil? (:place-busy (:state (ui/handle (assoc s :place-busy true)
                                              [:place-save-result {:ok false :code "place_invalid"}])))))
    (is (= :nav (fx-op (assoc s :page :map-place) [:emaff-import-result {:ok true}])))
    (is (= :api (fx-op (assoc s :page :map) [:emaff-import-result {:ok true :code "emaff_partial"}])))
    (is (= :html (fx-op (assoc s :page :map-place) [:emaff-import-result {:ok false :code "emaff_busy"}])))
    (is (re-find #"取り込んでいます" (get-in (ui/handle (assoc s :page :map-place)
                                                       [:emaff-import-result {:ok false :code "emaff_busy"}])
                                            [:state :flash :text])))
    (is (true? (get-in (ui/handle (assoc s :page :map-place) [:emaff-import-result {:ok false :code "emaff_unavailable"}])
                       [:state :flash :error?])))
    (is (= :html (fx-op (assoc s :page :map-place) [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                                          :note "n"
                                                                          :bbox {:west 1 :south 2 :east 3 :north 4}}])))
    (is (= "aerial" (:place-preview (:state (ui/handle (assoc s :page :map-place)
                                                       [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                                               :bbox {:west 1 :south 2 :east 3 :north 4}}])))))
    (is (nil? (get-in (ui/handle (assoc s :page :map-place :form nil)
                                 [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                         :bbox {:west 1 :south 2 :east 3 :north 4}}])
                      [:state :form :preview-note])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :form nil)
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:west 1 :south 2 :east 3 :north 4}}])
                       [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :form {})
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:west 1}}])
                       [:state :form :west])))
    (is (= "2" (get-in (ui/handle (assoc s :page :map-place :form {})
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:south 2}}])
                       [:state :form :south])))
    (is (= "3" (get-in (ui/handle (assoc s :page :map-place :form {})
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:east 3}}])
                       [:state :form :east])))
    (is (= "4" (get-in (ui/handle (assoc s :page :map-place :form {})
                                  [:place-preview-result {:ok true :source "gsi" :kind "aerial"
                                                          :bbox {:north 4}}])
                       [:state :form :north])))
    (is (nil? (get-in (ui/handle (assoc s :page :map-place :form {})
                                 [:place-preview-result {:ok true :source "gsi" :kind "aerial" :note "n"}])
                      [:state :form :preview-note])))
    (is (true? (get-in (ui/handle (assoc s :page :map-place) [:place-preview-result {:ok false :code "place_invalid"}])
                       [:state :flash :error?])))
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
    (is (= :api (fx-op (assoc s :page :map) [:path {:path "/home" :search ""}])))
    (is (= :api (fx-op (assoc s :page :home) [:path {:path "/gantt" :search ""}])))
    (let [fx (:fx (ui/handle (assoc (dissoc s :session) :page :home) [:path {:path "/map" :search ""}]))]
      (is (= :restore-guest-lang (ffirst fx)))
      (is (= :session (ffirst (rest fx)))))
    (is (= :restore-guest-lang (fx-op (assoc s :page :home :kind "user") [:path {:path "/admin/users" :search ""}])))
    (let [b (ui/handle (ui/init-state) [:boot {:path "/map" :search "" :narrow? true}])]
      (is (true? (get-in b [:state :narrow?]))))
    (is (= :html (fx-op s [:submit {:act "save-place" :form {:west "1" :south "2" :east "3" :north "4"}}])))
    (is (true? (:place-busy (:state (ui/handle s [:submit {:act "save-place"
                                                           :form {:west "1" :south "2" :east "3" :north "4"}}])))))
    (is (= :html (fx-op (assoc s :form nil)
                        [:submit {:act "save-place" :form {:west "1" :south "2" :east "3" :north "4"}}])))
    (is (= :html (fx-op (assoc s :place-busy true)
                        [:submit {:act "save-place" :form {:west "1" :south "2" :east "3" :north "4"}}])))
    (is (= :api (fx-op s [:submit {:act "preview-place" :form {:west "1"}}])))
    (is (= :api (fx-op (assoc s :form nil) [:submit {:act "preview-place" :form {:west "1"}}])))
    (is (= :html (fx-op (assoc s :place-busy true) [:submit {:act "preview-place" :form {:west "1"}}])))
    (is (= :html (fx-op (assoc s :page :map-place :place-preview "aerial")
                        [:submit {:act "cancel-place-preview" :form {:west "140" :south "35" :east "141" :north "36"}}])))
    (is (nil? (:place-preview (:state (ui/handle (assoc s :page :map-place :place-preview "aerial")
                                                 [:submit {:act "cancel-place-preview"
                                                           :form {:west "140" :south "35" :east "141" :north "36"}}])))))
    (is (= "140" (get-in (ui/handle (assoc s :page :map-place :place-preview "aerial")
                                    [:submit {:act "cancel-place-preview"
                                              :form {:west "140" :south "35" :east "141" :north "36"}}])
                         [:state :form :west])))
    (is (= "1" (get-in (ui/handle (assoc s :page :map-place :place-preview "aerial" :form nil)
                                  [:submit {:act "cancel-place-preview"
                                            :form {:west "1" :south "2" :east "3" :north "4"}}])
                       [:state :form :west])))
    (is (= :html (fx-op s [:submit {:act "emaff-import" :form {}}])))
    (is (= :api (ffirst (rest (:fx (ui/handle s [:submit {:act "emaff-import" :form {}}]))))))
    (is (= :html (fx-op (assoc s :place-busy true) [:submit {:act "emaff-import" :form {}}])))
    (is (= :api (fx-op s [:submit {:act "create-field" :form {:name "n" :geojson "{\"type\":\"Polygon\"}"}}])))
    (is (= :api (fx-op s [:submit {:act "update-field" :form {:id "1" :name "n" :geojson "{\"type\":\"Polygon\"}"}}])))
    (is (= :api (fx-op s [:submit {:act "update-field" :form {:id "1"}}])))
    (is (= :api (fx-op s [:submit {:act "delete-field" :form {:id "1"}}])))
    (is (= :html (fx-op (assoc s :page :fields :narrow? true) [:session-loaded {:ok true :email "a"}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :map :kind "user") [:session-loaded {:ok false}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :map-place :kind "user") [:session-loaded {:ok false}])))
    (is (= :nav (fx-op (assoc (ui/init-state) :page :fields :kind "user") [:session-loaded {:ok false}])))
    (is (= :html (fx-op s [:submit {:act "split-field" :form {:id "1"}}])))
    (is (= :html (fx-op s [:submit {:act "split-field" :form {:id "1" :polygons "[1]"}}])))
    (is (= :html (fx-op s [:submit {:act "split-field" :form {:polygons "[{},{}]"}}])))
    (is (= :api (fx-op s [:submit {:act "split-field" :form {:id "1" :polygons "[{},{}]"}}])))
    (is (= :api (fx-op s [:submit {:act "split-field" :form {:id "1" :line "{\"type\":\"LineString\"}"}}])))
    (is (= :html (fx-op s [:submit {:act "merge-fields" :form {:keep_id "1"}}])))
    (is (= :api (fx-op s [:submit {:act "merge-fields" :form {:keep_id "1" :ids "[1,2]"}}])))
    (is (= :upload (fx-op s [:submit {:act "import-fields" :form {:file "x"}}])))
    (is (= :upload (fx-op s [:submit {:act "upload-basemap" :form {:kind "aerial" :file "x"}}])))
    (is (= :api (fx-op s [:submit {:act "save-image-extent" :form {:west "1" :south "2" :east "3" :north "4"}}])))
    (is (= :api (fx-op s [:image-save-result {:ok true}])))
    (is (true? (get-in (ui/handle s [:image-save-result {:ok false :code "place_invalid"}]) [:state :flash :error?])))))
