(ns isas.ui
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

(def messages
  {:user-login-title "利用者ログイン"
   :admin-login-title "管理者ログイン"
   :login-failed "メールアドレスまたはパスワードが違います"
   :reset-requested "案内を送りました。届かないときは、招待されていないか、入口が違う可能性があります"
   :reset-invalid "この案内は使えません。もう一度やり直してください"
   :invite-ok "初期パスワードを相手に伝えてください。この画面を離れると同じ文字列は出せません"
   :invite-duplicate-user "このメールアドレスは、すでに利用者として招待されています"
   :invite-duplicate-admin "このメールアドレスは管理者のため、利用者として招待できません"
   :invite-invalid "メールアドレスの形式ではありません"
   :user-home "利用者として入っています"
   :admin-home "管理者として入っています。圃場は持ちません"
   :password-mismatch "確認用パスワードが一致しません"
   :password-too-short "パスワードは8文字以上にしてください"
   :password-wrong "今のパスワードが違います"
   :password-ok "パスワードを変更しました"
   :unauthorized "入っていません"
   :api-error "通信できませんでした"
   :fields-title "圃場台帳"
   :map-title "地図"
   :place-needed "先に作業場所の範囲を決めてください"
   :place-move "枠の中をドラッグで移動し、ホイールまたは左上の＋／−で拡大します。灰色は下地なしです"
   :place-set "この範囲を作業場所にする"
   :phone-map "台帳と地図の編集はパソコンで開いてください"
   :shape-not-area "閉じた形で、面積が取れるものにしてください"
   :import-invalid "このファイルは区画として読めません"
   :forbidden "この入口では使えません"
   :place-invalid "作業場所の範囲が正しくありません"
   :basemap-kind "下地の種類が違います"
   :basemap-missing "その下地はまだありません"
   :field-not-found "その圃場はありません"
   :split-too-few "分割は2枚以上にしてください"
   :merge-too-few "合筆は2枚以上選んでください"
   :merge-keep-missing "残す圃場を対象に含めてください"})

(defn code-message [code]
  (case code
    "login_failed" (:login-failed messages)
    "reset_invalid" (:reset-invalid messages)
    "invite_invalid_email" (:invite-invalid messages)
    "invite_duplicate_user" (:invite-duplicate-user messages)
    "invite_duplicate_admin" (:invite-duplicate-admin messages)
    "password_mismatch" (:password-mismatch messages)
    "password_too_short" (:password-too-short messages)
    "password_wrong" (:password-wrong messages)
    "unauthorized" (:unauthorized messages)
    "forbidden" (:forbidden messages)
    "place_unset" (:place-needed messages)
    "place_invalid" (:place-invalid messages)
    "shape_not_area" (:shape-not-area messages)
    "basemap_kind" (:basemap-kind messages)
    "basemap_missing" (:basemap-missing messages)
    "field_not_found" (:field-not-found messages)
    "split_too_few" (:split-too-few messages)
    "merge_too_few" (:merge-too-few messages)
    "merge_keep_missing" (:merge-keep-missing messages)
    "import_invalid" (:import-invalid messages)
    (:api-error messages)))

(defn esc [s]
  (-> (str (or s ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn read-json-str [s]
  (cond
    (nil? s) nil
    (or (map? s) (sequential? s)) s
    :else
    #?(:clj (try (json/read-str (str s) :key-fn keyword) (catch Exception _ nil))
       :cljs (try (js->clj (js/JSON.parse s) :keywordize-keys true) (catch :default _ nil)))))

(defn parse-query [search]
  (let [q (if (str/starts-with? (or search "") "?") (subs search 1) (or search ""))]
    (if (str/blank? q)
      {}
      (->> (str/split q #"&")
           (remove str/blank?)
           (map (fn [part]
                  (let [i (str/index-of part "=")]
                    (if i
                      [(keyword (subs part 0 i)) (subs part (inc i))]
                      [(keyword part) ""]))))
           (into {})))))

(defn route-for [path]
  (case path
    "/" {:page :login :kind "user"}
    "/reset/request" {:page :reset-request :kind "user"}
    "/reset" {:page :reset :kind "user"}
    "/home" {:page :home :kind "user"}
    "/invite" {:page :invite :kind "user"}
    "/password" {:page :password :kind "user"}
    "/fields" {:page :fields :kind "user"}
    "/map" {:page :map :kind "user"}
    "/map/place" {:page :map-place :kind "user"}
    "/admin" {:page :login :kind "admin"}
    "/admin/reset/request" {:page :reset-request :kind "admin"}
    "/admin/reset" {:page :reset :kind "admin"}
    "/admin/home" {:page :home :kind "admin"}
    "/admin/invite" {:page :invite :kind "admin"}
    "/admin/users" {:page :users :kind "admin"}
    "/admin/password" {:page :password :kind "admin"}
    {:page :unknown :kind "user"}))

(defn login-path [kind]
  (if (= kind "admin") "/admin" "/"))

(defn home-path [kind]
  (if (= kind "admin") "/admin/home" "/home"))

(defn needs-auth? [page]
  (contains? #{:home :invite :password :users :fields :map :map-place} page))

(defn init-state []
  {:path "/"
   :search ""
   :page :login
   :kind "user"
   :session nil
   :flash nil
   :busy false
   :initial-password nil
   :users []
   :fields []
   :place nil
   :basemaps []
   :narrow? false
   :form {}})

(defn flash-html [state]
  (when-let [f (:flash state)]
    (str "<p class=\"flash " (if (:error? f) "error" "ok") "\">" (esc (:text f)) "</p>")))

(defn layout [title body]
  (str "<main><h1>" (esc title) "</h1>" body "</main>"))

(defn nav-user []
  "<nav><a data-nav href=\"/home\">ホーム</a><a data-nav href=\"/fields\">圃場台帳</a><a data-nav href=\"/map\">地図</a><a data-nav href=\"/invite\">招待</a><a data-nav href=\"/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

(defn nav-admin []
  "<nav><a data-nav href=\"/admin/home\">ホーム</a><a data-nav href=\"/admin/invite\">招待</a><a data-nav href=\"/admin/users\">取消し</a><a data-nav href=\"/admin/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

(defn login-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (:admin-login-title messages) (:user-login-title messages))
        reset (if admin? "/admin/reset/request" "/reset/request")
        other (if admin? ["/" "利用者入口"] ["/admin" "管理者入口"])]
    (layout title
            (str (flash-html state)
                 "<form data-act=\"login\" method=\"post\">"
                 "<label>メールアドレス<input name=\"email\" type=\"email\" required></label>"
                 "<label>パスワード<input name=\"password\" type=\"password\" required></label>"
                 "<button type=\"submit\">入る</button></form>"
                 "<p><a data-nav href=\"" reset "\">パスワードを忘れた</a></p>"
                 "<p><a data-nav href=\"" (first other) "\">" (esc (second other)) "</a></p>"))))

(defn reset-request-view [state]
  (layout "パスワード再設定の依頼"
          (str (flash-html state)
               "<form data-act=\"reset-request\" method=\"post\">"
               "<label>メールアドレス<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">案内を送る</button></form>"
               "<p><a data-nav href=\"" (login-path (:kind state)) "\">ログインへ</a></p>")))

(defn reset-view [state]
  (layout "新しいパスワード"
          (str (flash-html state)
               "<form data-act=\"reset-complete\" method=\"post\">"
               "<label>新しいパスワード<input name=\"password\" type=\"password\" required></label>"
               "<label>新しいパスワード（確認）<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">決める</button></form>")))

(defn home-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (:admin-home messages) (:user-home messages))]
    (layout title
            (str (if admin? (nav-admin) (nav-user))
                 (flash-html state)
                 "<p>" (esc (get-in state [:session :email])) "</p>"))))

(defn invite-view [state]
  (layout "利用者を招待"
          (str (if (= "admin" (:kind state)) (nav-admin) (nav-user))
               (flash-html state)
               (when-let [pw (:initial-password state)]
                 (str "<p>" (esc (:invite-ok messages)) "</p><p>初期パスワード: <code>" (esc pw) "</code></p>"))
               "<form data-act=\"invite\" method=\"post\">"
               "<label>相手のメールアドレス<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">招待する</button></form>")))

(defn password-view [state]
  (layout "パスワード変更"
          (str (if (= "admin" (:kind state)) (nav-admin) (nav-user))
               (flash-html state)
               "<form data-act=\"password\" method=\"post\">"
               "<label>今のパスワード<input name=\"current_password\" type=\"password\" required></label>"
               "<label>新しいパスワード<input name=\"password\" type=\"password\" required></label>"
               "<label>新しいパスワード（確認）<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">変える</button></form>")))

(defn users-view [state]
  (layout "招待の取消し"
          (str (nav-admin)
               (flash-html state)
               "<ul>"
               (apply str
                      (for [u (:users state)]
                        (str "<li>" (esc (:email u))
                             "<form data-act=\"revoke\" method=\"post\">"
                             "<input type=\"hidden\" name=\"user_id\" value=\"" (esc (:id u)) "\">"
                             "<button type=\"submit\">取り消す</button></form></li>")))
               "</ul>")))

(defn unknown-view []
  (layout "ISAS" "<p>このページはありません。</p><p><a data-nav href=\"/\">利用者入口</a></p>"))

(defn phone-view [state]
  (layout (:map-title messages)
          (str (nav-user) (flash-html state) "<p>" (esc (:phone-map messages)) "</p>")))

(defn fields-view [state]
  (layout (:fields-title messages)
          (str (nav-user)
               (flash-html state)
               "<table><thead><tr><th>名前</th><th>ha</th><th>㎡</th><th></th></tr></thead><tbody>"
               (apply str
                      (for [f (:fields state)]
                        (str "<tr><td>" (esc (:name f)) "</td>"
                             "<td>" (esc (:area_ha f)) "</td>"
                             "<td>" (esc (:area_m2 f)) "</td>"
                             "<td><form data-act=\"delete-field\" method=\"post\">"
                             "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id f)) "\">"
                             "<button type=\"submit\">削除</button></form></td></tr>")))
               "</tbody></table>"
               "<p><a data-nav href=\"/map\">地図へ</a></p>")))

(defn- basemap-ready? [state kind]
  (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state))))

(defn map-place-view [state]
  (layout (:map-title messages)
          (str (nav-user)
               (flash-html state)
               "<p>" (esc (:place-needed messages)) "</p>"
               "<p>" (esc (:place-move messages)) "</p>"
               "<p id=\"place-extent\"></p>"
               "<form data-act=\"save-place\" method=\"post\">"
               "<input type=\"hidden\" name=\"west\" value=\"" (esc (get-in state [:form :west] "129")) "\">"
               "<input type=\"hidden\" name=\"south\" value=\"" (esc (get-in state [:form :south] "26")) "\">"
               "<input type=\"hidden\" name=\"east\" value=\"" (esc (get-in state [:form :east] "146")) "\">"
               "<input type=\"hidden\" name=\"north\" value=\"" (esc (get-in state [:form :north] "46")) "\">"
               "<button type=\"submit\">" (esc (:place-set messages)) "</button></form>"
               "<div id=\"ol-map\" class=\"ol-map\"></div>")))

(defn map-view [state]
  (layout (:map-title messages)
          (str (nav-user)
               (flash-html state)
               "<p><a data-nav href=\"/map/place\">作業場所を変える</a></p>"
               "<div class=\"toolbar\">"
               "<button type=\"button\" data-map=\"draw\">手描き</button>"
               "<button type=\"button\" data-map=\"edit\">修正</button>"
               "<button type=\"button\" data-map=\"split\">分割</button>"
               "<button type=\"button\" data-map=\"merge\">合筆</button>"
               (apply str
                      (for [[k label] [["aerial" "空中写真"] ["standard" "標準地図"] ["satellite" "衛星"]]]
                        (if (basemap-ready? state k)
                          (str "<button type=\"button\" data-map=\"basemap\" data-kind=\"" k "\">" label "</button>")
                          "")))
               "</div>"
               "<form data-act=\"create-field\" method=\"post\">"
               "<label>名前<input name=\"name\" required></label>"
               "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
               "<button type=\"submit\">圃場を保存</button></form>"
               "<form data-act=\"update-field\" method=\"post\">"
               "<input type=\"hidden\" name=\"id\" value=\"" (esc (get-in state [:form :id] "")) "\">"
               "<label>名前<input name=\"name\" value=\"" (esc (get-in state [:form :name] "")) "\"></label>"
               "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
               "<button type=\"submit\">形と名前を保存</button></form>"
               "<form data-act=\"split-field\" method=\"post\">"
               "<input type=\"hidden\" name=\"id\" value=\"" (esc (get-in state [:form :split-id] "")) "\">"
               "<input type=\"hidden\" name=\"polygons\" value=\"" (esc (get-in state [:form :polygons] "[]")) "\">"
               "<button type=\"submit\">分割を保存</button></form>"
               "<form data-act=\"merge-fields\" method=\"post\">"
               "<input type=\"hidden\" name=\"keep_id\" value=\"" (esc (get-in state [:form :keep_id] "")) "\">"
               "<input type=\"hidden\" name=\"ids\" value=\"" (esc (get-in state [:form :ids] "[]")) "\">"
               "<button type=\"submit\">合筆する</button></form>"
               "<form data-act=\"import-fields\" method=\"post\" enctype=\"multipart/form-data\">"
               "<label>区画ファイル<input name=\"file\" type=\"file\" accept=\".json,.geojson,application/geo+json\"></label>"
               "<button type=\"submit\">取り込む</button></form>"
               "<form data-act=\"upload-basemap\" method=\"post\" enctype=\"multipart/form-data\">"
               "<label>下地"
               "<select name=\"kind\">"
               "<option value=\"aerial\">空中写真</option>"
               "<option value=\"standard\">標準地図</option>"
               "<option value=\"satellite\">衛星</option>"
               "</select></label>"
               "<input name=\"file\" type=\"file\" accept=\"image/jpeg,image/png,.jpg,.jpeg,.png\">"
               "<button type=\"submit\">下地を取り込む</button></form>"
               "<div id=\"ol-map\" class=\"ol-map\"></div>")))

(defn render [state]
  (if (and (:narrow? state) (contains? #{:fields :map :map-place} (:page state)))
    (phone-view state)
    (case (:page state)
      :login (login-view state)
      :reset-request (reset-request-view state)
      :reset (reset-view state)
      :home (home-view state)
      :invite (invite-view state)
      :password (password-view state)
      :users (users-view state)
      :fields (fields-view state)
      :map (if (:place state) (map-view state) (map-place-view state))
      :map-place (map-place-view state)
      (unknown-view))))

(defn apply-route [state path search]
  (let [r (route-for path)]
    (assoc state
           :path path
           :search (or search "")
           :page (:page r)
           :kind (:kind r)
           :initial-password nil)))

(defn guarded [state]
  (cond
    (and (needs-auth? (:page state)) (nil? (:session state)))
    {:state (assoc state :flash {:error? true :text (:unauthorized messages)})
     :fx [[:nav (login-path (:kind state))]]}

    (and (= :login (:page state)) (:session state))
    {:state state
     :fx [[:nav (home-path (:kind state))]]}

    :else
    {:state state
     :fx [[:html (render state)]]}))

(defn boot [state {:keys [path search narrow?]}]
  (let [s (apply-route (assoc state :narrow? (boolean narrow?)) path search)
        token (:token (parse-query search))]
    {:state (assoc s :form (if token {:token token} {}))
     :fx [[:session (:kind s)]]}))

(defn session-loaded [state body]
  (let [s (if (:ok body)
            (assoc state :session {:email (:email body)})
            (assoc state :session nil))]
    (cond
      (and (= :users (:page s)) (:session s))
      {:state s :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]}

      (and (#{:map :map-place} (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}

      (and (= :fields (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      :else
      (guarded s))))

(defn place-loaded [state body]
  (let [s (assoc state :place (when (:ok body)
                                (select-keys body [:west :south :east :north])))]
    {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}))

(defn fields-loaded [state body]
  (let [s (assoc state :fields (or (:fields body) []))]
    (if (#{:map :map-place} (:page s))
      {:state s :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]}
      (guarded s))))

(defn basemaps-loaded [state body]
  (guarded (assoc state :basemaps (or (:basemaps body) []))))

(defn after-place-save [state body]
  (if (:ok body)
    {:state (assoc state :flash nil)
     :fx [[:nav "/map"]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-field-save [state body]
  (if (:ok body)
    {:state (assoc state :flash {:error? false :text "保存しました"} :form {})
     :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-field-delete [state body]
  (if (:ok body)
    {:state state :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-basemap-upload [state body]
  (if (:ok body)
    {:state (assoc state :flash {:error? false :text "下地を取り込みました"})
     :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn users-loaded [state body]
  (guarded (assoc state :users (or (:users body) []))))

(defn after-login [state body]
  (if (:ok body)
    {:state (assoc state :session {:email (:email body)} :flash nil)
     :fx [[:nav (home-path (:kind state))]]}
    {:state (assoc state :flash {:error? true :text (code-message (:code body))})
     :fx [[:html (render (assoc state :flash {:error? true :text (code-message (:code body))}))]]}))

(defn after-logout [state]
  {:state (assoc state :session nil :flash nil)
   :fx [[:nav (login-path (:kind state))]]})

(defn after-reset-request [state]
  (let [s (assoc state :flash {:error? false :text (:reset-requested messages)})]
    {:state s :fx [[:html (render s)]]}))

(defn after-reset-complete [state body]
  (if (:ok body)
    {:state (assoc state :flash nil)
     :fx [[:nav (login-path (:kind state))]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-invite [state body]
  (if (:ok body)
    (let [s (assoc state
                   :initial-password (:initial_password body)
                   :flash {:error? false :text (:invite-ok messages)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-password [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (:password-ok messages)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-revoke [state]
  {:state state :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]})

(defn after-api-error [state]
  (let [s (assoc state :flash {:error? true :text (:api-error messages)})]
    {:state s :fx [[:html (render s)]]}))

(defn handle [state msg]
  (let [[op arg] (if (vector? msg) msg [msg nil])]
    (case op
      :boot (boot state arg)
      :session-loaded (session-loaded state arg)
      :users-loaded (users-loaded state arg)
      :place-loaded (place-loaded state arg)
      :fields-loaded (fields-loaded state arg)
      :basemaps-loaded (basemaps-loaded state arg)
      :place-save-result (after-place-save state arg)
      :field-save-result (after-field-save state arg)
      :field-delete-result (after-field-delete state arg)
      :basemap-upload-result (after-basemap-upload state arg)
      :login-result (after-login state arg)
      :logout-result (after-logout state)
      :reset-request-result (after-reset-request state)
      :reset-complete-result (after-reset-complete state arg)
      :invite-result (after-invite state arg)
      :password-result (after-password state arg)
      :revoke-result (after-revoke state)
      :api-error (after-api-error state)
      :narrow
      (let [s (assoc state :narrow? (boolean (:narrow? arg)))]
        (if (:session s)
          (session-loaded s {:ok true :email (get-in s [:session :email])})
          (guarded s)))
      :path
      (let [s (apply-route (assoc state :session (:session state) :flash nil) (:path arg) (:search arg))]
        (if (:session s)
          (session-loaded s {:ok true :email (get-in s [:session :email])})
          (guarded s)))
      :submit
      (let [act (:act arg)
            form (:form arg)
            kind (:kind state)]
        (case act
          "login" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/login" "/api/user/login") form :login-result]]}
          "logout" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/logout" "/api/user/logout") {} :logout-result]]}
          "reset-request" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password/reset/request" "/api/user/password/reset/request") form :reset-request-result]]}
          "reset-complete" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password/reset" "/api/user/password/reset")
                                              (assoc form :token (or (:token form) (:token (parse-query (:search state)))))
                                              :reset-complete-result]]}
          "invite" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/invite" "/api/user/invite") form :invite-result]]}
          "password" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password" "/api/user/password") form :password-result]]}
          "revoke" {:state state :fx [[:api "POST" "/api/admin/users/revoke" form :revoke-result]]}
          "save-place" {:state state :fx [[:api "PUT" "/api/user/place" form :place-save-result]]}
          "create-field" {:state state :fx [[:api "POST" "/api/user/fields"
                                            {:name (:name form)
                                             :geojson (read-json-str (:geojson form))}
                                            :field-save-result]]}
          "update-field" {:state state :fx [[:api "PUT" (str "/api/user/fields/" (:id form))
                                            (cond-> {}
                                              (contains? form :name) (assoc :name (:name form))
                                              (not (str/blank? (str (:geojson form))))
                                              (assoc :geojson (read-json-str (:geojson form))))
                                            :field-save-result]]}
          "delete-field" {:state state :fx [[:api "DELETE" (str "/api/user/fields/" (:id form)) nil :field-delete-result]]}
          "split-field" {:state state :fx [[:api "POST" (str "/api/user/fields/" (:id form) "/split")
                                            {:polygons (or (read-json-str (:polygons form)) [])}
                                            :field-save-result]]}
          "merge-fields" {:state state :fx [[:api "POST" "/api/user/fields/merge"
                                            {:keep_id (:keep_id form)
                                             :ids (or (read-json-str (:ids form)) [])}
                                            :field-save-result]]}
          "import-fields" {:state state :fx [[:upload "POST" "/api/user/fields/import" form :field-save-result]]}
          "upload-basemap" {:state state :fx [[:upload "PUT" (str "/api/user/basemaps/" (:kind form)) form :basemap-upload-result]]}
          {:state state :fx [[:html (render state)]]}))
      {:state state :fx [[:html (render state)]]})))
