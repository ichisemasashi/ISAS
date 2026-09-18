(ns isas.ui
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])
            #?(:clj [isas.time :as time])))

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
   :place-change "いまの作業場所を変えられます。地理院地図で範囲を直し、空中写真で確認してから確定してください"
   :place-move "地理院地図を動かして範囲を決め、空中写真で確認してから確定してください"
   :place-set "この範囲を作業場所にする"
   :place-preview "空中写真で最終確認"
   :place-preview-note "確認用の空中写真です。この範囲でよければ確定、直すなら地理院地図に戻ってください"
   :place-gsi-attr "地図：国土地理院"
   :place-back-gsi "地理院地図に戻って範囲を直す"
   :place-saving "作業場所を保存し、下地を取り込んでいます。完了するまでお待ちください"
   :emaff-import "この範囲の下地を自動で取り込む"
   :emaff-import-ok "下地の自動取込が終わりました。区画は手作業で取り込んでください"
   :emaff-unavailable "下地の自動取込ができませんでした。手作業の取込を使ってください"
   :emaff-partial "下地の一部だけ自動取込できました。区画は手作業で取り込んでください"
   :emaff-busy "いま下地を取り込んでいます。終わるまで待ってから操作してください"
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
   :merge-keep-missing "残す圃場を対象に含めてください"
   :map-hint "いま必要な操作のボタンだけ出しています。やめるとメニューに戻ります"
   :map-hint-browse "作業名を入れるか、塗り・圃場の形・下地のどれかを選んでください"
   :map-hint-paint "作業名を入れ、圃場をクリックしてからブラシで塗ります。塗り終わったら地図をドラッグして動かせます。圃場の形を直すときは「圃場を直す」"
   :map-hint-draw "閉じた形を描き、名前を付けて「圃場を保存」してください"
   :map-hint-edit "頂点を動かして「形と名前を保存」してください"
   :map-hint-split "分割する圃場をクリックして選び、そのあと圃場を横切る線を引いて「分割を保存」してください"
   :map-hint-merge "残す圃場をクリックし、続けて合筆する圃場をクリックして「合筆する」を押してください"
   :map-hint-import "区画ファイルを選んで取り込んでください"
   :map-hint-image "下地を圃場の形に合わせ、「下地の位置を保存」してください。3種とも同じ位置です"
   :map-do-paint "塗りをする"
   :map-do-fields "圃場を直す"
   :map-do-basemap "下地"
   :map-do-import "区画取込"
   :map-cancel "やめる"
   :image-shift-west "下地を西へ"
   :image-shift-east "下地を東へ"
   :image-shift-south "下地を南へ"
   :image-shift-north "下地を北へ"
   :image-scale-in "下地を縮小"
   :image-scale-out "下地を拡大"
   :image-reset "下地を作業場所の範囲に戻す"
   :image-save "下地の位置を保存"
   :image-ok "下地の位置を保存しました"
   :work-name "作業名"
   :work-name-needed "作業名を入れてから塗ってください"
   :work-name-too-long "作業名は100文字以内にしてください"
   :work-name-see "この作業名で見る"
   :paint-confirm "塗りを確定する"
   :paint-discard "下書きを捨てる"
   :paint-complete "この圃場をこの作業名で全面完了にする"
   :paint-delete "この塗りを消す"
   :paint-delete-all "この圃場のこの作業名の塗りを全部消す"
   :split-has-paint "塗りが残っている圃場は分割できません。塗りを消してから行ってください"
   :merge-has-paint "塗りが残っている圃場は合筆できません。塗りを消してから行ってください"
   :status-none "未"
   :status-partial "一部"
   :status-done "済"
   :paint-empty "圃場の内側に塗れる場所がありません"
   :paint-not-found "その塗りはありません"
   :map-hint-brush "ブラシを押してからなぞります。一筆ごとに終わり、そのあと地図をドラッグできます"
   :paint-ok "塗りを保存しました"
   :gantt-title "ガント"
   :phone-gantt "ガントの編集はパソコンで開いてください"
   :gantt-no-fields "圃場が1枚以上あるときだけ、ガントを使えます"
   :gantt-work-needed "対象圃場がある行は、作業名を入れてください"
   :gantt-time-order "終了は開始より後にしてください"
   :gantt-add "予定を足す"
   :gantt-title-label "題名"
   :gantt-start "開始"
   :gantt-end "終了"
   :gantt-targets "対象圃場"
   :gantt-axis-day "日"
   :gantt-axis-week "週"
   :gantt-axis-month "月"
   :gantt-percent-unit "％"
   :gantt-dim-color "#e8e8e8"
   :title-required "題名を入れてください"
   :title-too-long "題名は200文字以内にしてください"
   :time-invalid "開始と終了は分までの日時にしてください"
   :gantt-not-found "その予定はありません"
   :orders-title "指示"
   :orders-create "指示を出す"
   :orders-close "この指示を閉じる"
   :orders-journal "日誌を書く"
   :others-title "他人の対象圃場"
   :relations-title "関係を切る"
   :order-no-fields "圃場が1枚以上あるときだけ、指示を出せます"
   :recipient-not-user "このメールアドレスの利用者には出せません"
   :recipient-self "自分は受け手に含められません"
   :relation-busy "進行中の指示がある二人は関係を切れません"
   :phone-orders-edit "指示の作成・修正・閉じはパソコンで開いてください"
   :phone-others "他人の対象圃場のまとめはパソコンで開いてください"
   :order-not-found "その指示はありません"
   :order-closed "この指示はすでに閉じています"
   :order-not-issuer "出した人だけができます"
   :order-not-recipient "受け手だけが日誌を書けます"
   :journal-exists "すでに日誌を書いています"
   :journal-required "日誌の本文を入れてください"
   :journal-too-long "日誌は2000文字以内にしてください"
   :body-too-long "依頼文は2000文字以内にしてください"
   :recipients-required "受け手を1人以上入れてください"
   :fields-required "対象圃場を1枚以上選んでください"
   :field-in-open-order "進行中の指示の対象なので、この操作はできません"
   :work-name-unrelated "関係した指示に無い作業名です"
   :relation-not-found "その二人の利用者は切れません"
   :relation-idle "いま見えている相手の圃場が無いので切りません"
   :order-open "進行中"
   :order-closed-label "閉じた"
   :order-work-date "日付"
   :order-start "開始"
   :order-end "終了"
   :order-body "依頼文"
   :order-recipients "受け手"
   :order-fields "対象圃場"
   :order-journals "日誌"
   :order-sent "出した指示"
   :order-received "受けた指示"
   :order-save "指示を保存"
   :order-update "依頼文と時刻を直す"
   :relations-cut-ok "関係を切りました"
   :order-time-invalid "日付は YYYY-MM-DD、時刻は HH:MM にしてください"
   :order-time-order "終了は開始より後にしてください"})

(def paint-colors
  {:none "#c8c8c8"
   :partial "#e6b800"
   :done "#2e7d32"
   :dim "#e8e8e8"})

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
    "emaff_unavailable" (:emaff-unavailable messages)
    "emaff_partial" (:emaff-partial messages)
    "emaff_busy" (:emaff-busy messages)
    "emaff_empty" (:emaff-unavailable messages)
    "shape_not_area" (:shape-not-area messages)
    "basemap_kind" (:basemap-kind messages)
    "basemap_missing" (:basemap-missing messages)
    "field_not_found" (:field-not-found messages)
    "split_too_few" (:split-too-few messages)
    "merge_too_few" (:merge-too-few messages)
    "merge_keep_missing" (:merge-keep-missing messages)
    "import_invalid" (:import-invalid messages)
    "work_name_required" (:work-name-needed messages)
    "work_name_too_long" (:work-name-too-long messages)
    "field_has_paint" (:split-has-paint messages)
    "paint_not_found" (:paint-not-found messages)
    "paint_empty" (:paint-empty messages)
    "no_fields" (:gantt-no-fields messages)
    "gantt_not_found" (:gantt-not-found messages)
    "title_required" (:title-required messages)
    "title_too_long" (:title-too-long messages)
    "time_invalid" (:time-invalid messages)
    "time_order" (:gantt-time-order messages)
    "order_not_found" (:order-not-found messages)
    "order_closed" (:order-closed messages)
    "order_not_issuer" (:order-not-issuer messages)
    "order_not_recipient" (:order-not-recipient messages)
    "journal_exists" (:journal-exists messages)
    "journal_required" (:journal-required messages)
    "journal_too_long" (:journal-too-long messages)
    "body_too_long" (:body-too-long messages)
    "recipients_required" (:recipients-required messages)
    "recipient_self" (:recipient-self messages)
    "recipient_not_user" (:recipient-not-user messages)
    "fields_required" (:fields-required messages)
    "field_in_open_order" (:field-in-open-order messages)
    "work_name_unrelated" (:work-name-unrelated messages)
    "relation_busy" (:relation-busy messages)
    "relation_not_found" (:relation-not-found messages)
    "relation_idle" (:relation-idle messages)
    (:api-error messages)))

(defn encode-q [s]
  #?(:clj (java.net.URLEncoder/encode (str s) "UTF-8")
     :cljs (js/encodeURIComponent (str s))))

(defn paints-query [work-name]
  (str "/api/user/paints?work_name=" (encode-q work-name)))

(defn field-paints-query [id work-name]
  (str "/api/user/fields/" id "/paints?work_name=" (encode-q work-name)))

(defn paint-block-text [act]
  (if (= "merge" act)
    (:merge-has-paint messages)
    (:split-has-paint messages)))

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

(defn shift-bbox [{:keys [west south east north]} dx dy]
  {:west (+ west dx)
   :south (+ south dy)
   :east (+ east dx)
   :north (+ north dy)})

(defn scale-bbox [{:keys [west south east north]} factor]
  (let [f (if (and (number? factor) (pos? factor)) (double factor) 1.0)
        cx (/ (+ west east) 2.0)
        cy (/ (+ south north) 2.0)
        hw (* (/ (- east west) 2.0) f)
        hh (* (/ (- north south) 2.0) f)]
    {:west (- cx hw)
     :south (- cy hh)
     :east (+ cx hw)
     :north (+ cy hh)}))

(defn image-bbox [place]
  (if (and place
           (every? number? [(:image_west place) (:image_south place)
                            (:image_east place) (:image_north place)]))
    {:west (:image_west place)
     :south (:image_south place)
     :east (:image_east place)
     :north (:image_north place)}
    (when place
      (select-keys place [:west :south :east :north]))))

(defn normalize-path [path]
  (let [p (or path "/")]
    (cond
      (str/blank? p) "/"
      (and (str/ends-with? p "/") (not= p "/")) (recur (subs p 0 (dec (count p))))
      :else p)))

(defn route-for [path]
  (let [p (normalize-path path)]
    (or (case p
          "/" {:page :login :kind "user"}
          "/reset/request" {:page :reset-request :kind "user"}
          "/reset" {:page :reset :kind "user"}
          "/home" {:page :home :kind "user"}
          "/invite" {:page :invite :kind "user"}
          "/password" {:page :password :kind "user"}
          "/fields" {:page :fields :kind "user"}
          "/map" {:page :map :kind "user"}
          "/map/place" {:page :map-place :kind "user"}
          "/gantt" {:page :gantt :kind "user"}
          "/orders" {:page :orders :kind "user"}
          "/orders/new" {:page :orders-new :kind "user"}
          "/others" {:page :others :kind "user"}
          "/admin" {:page :login :kind "admin"}
          "/admin/reset/request" {:page :reset-request :kind "admin"}
          "/admin/reset" {:page :reset :kind "admin"}
          "/admin/home" {:page :home :kind "admin"}
          "/admin/invite" {:page :invite :kind "admin"}
          "/admin/users" {:page :users :kind "admin"}
          "/admin/password" {:page :password :kind "admin"}
          "/admin/relations" {:page :relations :kind "admin"}
          "/admin/fields" {:page :fields :kind "user"}
          "/admin/map" {:page :map :kind "user"}
          "/admin/map/place" {:page :map-place :kind "user"}
          nil)
        (when-let [[_ id] (re-matches #"/orders/(\d+)" p)]
          {:page :order :kind "user" :order-id id})
        {:page :unknown :kind "user"})))

(defn login-path [kind]
  (if (= kind "admin") "/admin" "/"))

(defn home-path [kind]
  (if (= kind "admin") "/admin/home" "/home"))

(defn needs-auth? [page]
  (contains? #{:home :invite :password :users :fields :map :map-place :gantt
               :orders :orders-new :order :others :relations} page))

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
   :form {}
   :work-names []
   :paint-data nil
   :last-field-act nil
   :map-mode nil
   :map-mode-parent nil
   :gantt-rows []
   :gantt-selected nil
   :gantt-progress nil
   :gantt-axis "day"
   :orders-sent []
   :orders-received []
   :order nil
   :order-map nil
   :order-id nil
   :others-fields []
   :others-work-names []
   :others-paint-data nil})

(defn map-mode [state]
  (let [m (:map-mode state)
        wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (cond
      (and m (not (str/blank? (str m)))) (str m)
      (str/blank? wn) "browse"
      :else "paint")))

(defn- map-hint-for [mode]
  (case (str mode)
    "browse" (:map-hint-browse messages)
    "paint" (:map-hint-paint messages)
    "draw" (:map-hint-draw messages)
    "edit" (:map-hint-edit messages)
    "split" (:map-hint-split messages)
    "merge" (:map-hint-merge messages)
    "import" (:map-hint-import messages)
    "basemap" (:map-hint-image messages)
    (:map-hint messages)))

(defn- mode-form [mode label]
  (str "<form data-act=\"set-map-mode\" method=\"post\" class=\"inline\">"
       "<input type=\"hidden\" name=\"mode\" value=\"" (esc mode) "\">"
       "<button type=\"submit\">" (esc label) "</button></form>"))

(defn- cancel-form []
  (mode-form "cancel" (:map-cancel messages)))

(defn flash-html [state]
  (when-let [f (:flash state)]
    (str "<p class=\"flash " (if (:error? f) "error" "ok") "\">" (esc (:text f)) "</p>")))

(defn layout [title body]
  (str "<main><h1>" (esc title) "</h1>" body "</main>"))

(defn nav-user []
  "<nav><a data-nav href=\"/home\">ホーム</a><a data-nav href=\"/fields\">圃場台帳</a><a data-nav href=\"/map\">地図</a><a data-nav href=\"/invite\">招待</a><a data-nav href=\"/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

(defn nav-admin []
  "<nav><a data-nav href=\"/admin/home\">ホーム</a><a data-nav href=\"/admin/invite\">招待</a><a data-nav href=\"/admin/users\">取消し</a><a data-nav href=\"/admin/relations\">関係を切る</a><a data-nav href=\"/admin/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

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
                 "<p>" (esc (get-in state [:session :email])) "</p>"
                 (when (and (not admin?) (seq (:fields state)))
                   (str "<p><a data-nav href=\"/gantt\">" (esc (:gantt-title messages)) "</a></p>"
                        "<p><a data-nav href=\"/orders/new\">" (esc (:orders-create messages)) "</a></p>"))
                 (when (not admin?)
                   (str "<p><a data-nav href=\"/orders\">" (esc (:orders-title messages)) "</a></p>"
                        "<p><a data-nav href=\"/others\">" (esc (:others-title messages)) "</a></p>"))))))
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
  (cond
    (= :orders-new (:page state))
    (layout (:orders-title messages)
            (str (nav-user) (flash-html state) "<p>" (esc (:phone-orders-edit messages)) "</p>"))

    (= :others (:page state))
    (layout (:others-title messages)
            (str (nav-user) (flash-html state) "<p>" (esc (:phone-others messages)) "</p>"))

    (= :gantt (:page state))
    (layout (:gantt-title messages)
            (str (nav-user) (flash-html state) "<p>" (esc (:phone-gantt messages)) "</p>"))

    :else
    (layout (:map-title messages)
            (str (nav-user) (flash-html state) "<p>" (esc (:phone-map messages)) "</p>"))))

(defn- order-status-label [status]
  (if (= "closed" status)
    (:order-closed-label messages)
    (:order-open messages)))

(defn- order-list-items [rows]
  (apply str
         (for [o rows]
           (str "<li><a data-nav href=\"/orders/" (esc (:id o)) "\">"
                (esc (:work_date o)) " "
                (esc (:work_name o)) "（" (esc (order-status-label (:status o))) "）"
                "</a></li>"))))

(defn orders-view [state]
  (layout (:orders-title messages)
          (str (nav-user)
               (flash-html state)
               (when (and (not (:narrow? state)) (seq (:fields state)))
                 (str "<p><a data-nav href=\"/orders/new\">" (esc (:orders-create messages)) "</a></p>"))
               "<h2>" (esc (:order-sent messages)) "</h2>"
               "<ul>" (order-list-items (:orders-sent state)) "</ul>"
               "<h2>" (esc (:order-received messages)) "</h2>"
               "<ul>" (order-list-items (:orders-received state)) "</ul>")))

(defn orders-new-view [state]
  (if (empty? (:fields state))
    (layout (:orders-title messages)
            (str (nav-user) (flash-html state)
                 "<p>" (esc (:order-no-fields messages)) "</p>"))
    (let [form (or (:form state) {})]
      (layout (:orders-create messages)
              (str (nav-user)
                   (flash-html state)
                   "<form data-act=\"create-order\" method=\"post\">"
                   "<label>" (esc (:order-work-date messages))
                   "<input name=\"work_date\" value=\"" (esc (:work_date form)) "\"></label>"
                   "<label>" (esc (:order-start messages))
                   "<input name=\"start_time\" value=\"" (esc (:start_time form)) "\"></label>"
                   "<label>" (esc (:order-end messages))
                   "<input name=\"end_time\" value=\"" (esc (:end_time form)) "\"></label>"
                   "<label>" (esc (:work-name messages))
                   "<input name=\"work_name\" list=\"work-name-list\" value=\"" (esc (:work_name form)) "\">"
                   "<datalist id=\"work-name-list\">"
                   (apply str (for [n (:work-names state)]
                                (str "<option value=\"" (esc n) "\">")))
                   "</datalist></label>"
                   "<label>" (esc (:order-body messages))
                   "<textarea name=\"body\">" (esc (:body form)) "</textarea></label>"
                   "<label>" (esc (:order-recipients messages))
                   "<input name=\"recipient_emails\" value=\"" (esc (:recipient_emails form)) "\"></label>"
                   "<fieldset><legend>" (esc (:order-fields messages)) "</legend>"
                   (apply str
                          (for [f (:fields state)]
                            (str "<label><input type=\"checkbox\" name=\"field_ids\" value=\"" (esc (:id f)) "\""
                                 (when (some #{(:id f) (str (:id f))} (or (:field_ids form) []))
                                   " checked")
                                 ">" (esc (:name f)) "</label>")))
                   "</fieldset>"
                   "<button type=\"submit\">" (esc (:order-save messages)) "</button></form>")))))

(defn- order-map-html [state]
  (let [m (:order-map state)
        fields (or (:fields m) [])
        ids (mapv :id fields)
        wn (or (:work_name m) (get-in state [:order :work_name]))]
    (when (seq fields)
      (str "<div id=\"ol-map\" class=\"ol-map\" data-order-mode=\"1\""
           " data-target-ids=\"" (esc (str/join "," ids)) "\""
           (when wn (str " data-work-name=\"" (esc wn) "\""))
           "></div>"))))

(defn- paint-status-label [st]
  (get {"none" (:status-none messages)
        "partial" (:status-partial messages)
        "done" (:status-done messages)}
       (str st)
       (str st)))

(defn order-view [state]
  (let [o (:order state)
        narrow? (:narrow? state)
        issuer? (= "issuer" (:role o))
        recipient? (= "recipient" (:role o))
        open? (= "open" (:status o))
        can-edit? (and issuer? open? (not narrow?))
        can-journal? (and recipient? open?
                          (not (some #(= (get-in state [:session :email]) (:author_email %))
                                     (or (:journals o) []))))
        map-by (into {} (map (fn [f] [(:id f) f]) (or (:fields (:order-map state)) [])))
        field-labels (for [f (or (:fields o) [])]
                       (let [st (:status (get map-by (:id f)))]
                         (str (:name f)
                              (when st (str "（" (paint-status-label st) "）")))))]
    (if-not o
      (layout (:orders-title messages)
              (str (nav-user) (flash-html state)
                   "<p>" (esc (:order-not-found messages)) "</p>"))
      (layout (:orders-title messages)
              (str (nav-user)
                   (flash-html state)
                   "<p>" (esc (:work_date o)) " "
                   (esc (:start_time o)) "〜" (esc (:end_time o))
                   " / " (esc (order-status-label (:status o))) "</p>"
                   "<p>" (esc (:work-name messages)) ": " (esc (:work_name o)) "</p>"
                   "<p>" (esc (:order-body messages)) ": " (esc (:body o)) "</p>"
                   "<p>" (esc (:order-recipients messages)) ": "
                   (esc (str/join ", " (or (:recipient_emails o) []))) "</p>"
                   "<p>" (esc (:order-fields messages)) ": "
                   (esc (str/join ", " field-labels)) "</p>"
                   (or (order-map-html state) "")
                   (when narrow?
                     (str "<p>" (esc (:phone-orders-edit messages)) "</p>"))
                   (when can-edit?
                     (str "<form data-act=\"update-order\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<label>" (esc (:order-work-date messages))
                          "<input name=\"work_date\" value=\"" (esc (:work_date o)) "\"></label>"
                          "<label>" (esc (:order-start messages))
                          "<input name=\"start_time\" value=\"" (esc (:start_time o)) "\"></label>"
                          "<label>" (esc (:order-end messages))
                          "<input name=\"end_time\" value=\"" (esc (:end_time o)) "\"></label>"
                          "<label>" (esc (:order-body messages))
                          "<textarea name=\"body\">" (esc (:body o)) "</textarea></label>"
                          "<button type=\"submit\">" (esc (:order-update messages)) "</button></form>"
                          "<form data-act=\"close-order\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<button type=\"submit\">" (esc (:orders-close messages)) "</button></form>"))
                   "<h2>" (esc (:order-journals messages)) "</h2>"
                   "<ul>"
                   (apply str
                          (for [j (:journals o)]
                            (str "<li>" (esc (:author_email j)) ": " (esc (:body j)) "</li>")))
                   "</ul>"
                   (when can-journal?
                     (str "<form data-act=\"post-journal\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<label>" (esc (:orders-journal messages))
                          "<textarea name=\"body\"></textarea></label>"
                          "<button type=\"submit\">" (esc (:orders-journal messages)) "</button></form>")))))))

(defn others-view [state]
  (let [form (or (:form state) {})
        wn (str/trim (str (or (:work_name form) "")))
        paint (:others-paint-data state)]
    (layout (:others-title messages)
            (str (nav-user)
                 (flash-html state)
                 "<form data-act=\"select-others-work-name\" method=\"post\">"
                 "<label>" (esc (:work-name messages))
                 "<input name=\"work_name\" list=\"others-work-list\" value=\"" (esc wn) "\">"
                 "<datalist id=\"others-work-list\">"
                 (apply str (for [n (:others-work-names state)]
                              (str "<option value=\"" (esc n) "\">")))
                 "</datalist></label>"
                 "<button type=\"submit\">" (esc (:work-name-see messages)) "</button></form>"
                 "<div id=\"ol-map\" class=\"ol-map\" data-others-mode=\"1\""
                 (when (seq (:others-fields state))
                   (str " data-target-ids=\""
                        (esc (str/join "," (map :id (:others-fields state)))) "\""))
                 (when (and paint wn)
                   (str " data-work-name=\"" (esc wn) "\""))
                 "></div>"
                 "<ul>"
                 (apply str
                        (for [f (:others-fields state)]
                          (let [st (when paint
                                     (some #(when (= (:id %) (:id f)) (:status %))
                                           (:fields paint)))]
                            (str "<li>" (esc (:name f)) " / " (esc (:owner_email f))
                                 (when st (str "（" (esc (get {:none (:status-none messages)
                                                                :partial (:status-partial messages)
                                                                :done (:status-done messages)}
                                                              (keyword st) st)) "）"))
                                 "</li>"))))
                 "</ul>"))))

(defn relations-view [state]
  (layout (:relations-title messages)
          (str (nav-admin)
               (flash-html state)
               "<form data-act=\"cut-relation\" method=\"post\">"
               "<label>メールアドレス A<input name=\"email_a\" type=\"email\" required></label>"
               "<label>メールアドレス B<input name=\"email_b\" type=\"email\" required></label>"
               "<button type=\"submit\">" (esc (:relations-title messages)) "</button></form>")))

(defn fields-view [state]
  (layout (:fields-title messages)
          (str (nav-user)
               (flash-html state)
               "<table><thead><tr><th>名前</th><th>ha</th><th>㎡</th><th></th></tr></thead><tbody>"
               (apply str
                      (for [f (:fields state)]
                        (str "<tr><td><form data-act=\"update-field\" method=\"post\">"
                             "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id f)) "\">"
                             "<input name=\"name\" value=\"" (esc (:name f)) "\">"
                             "<button type=\"submit\">名前を保存</button></form></td>"
                             "<td>" (esc (:area_ha f)) "</td>"
                             "<td>" (esc (:area_m2 f)) "</td>"
                             "<td><form data-act=\"delete-field\" method=\"post\">"
                             "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id f)) "\">"
                             "<button type=\"submit\">削除</button></form></td></tr>")))
               "</tbody></table>"
               "<p><a data-nav href=\"/map\">地図へ</a></p>")))
(defn- basemap-ready? [state kind]
  (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state))))

(defn- basemap-kind-buttons [state]
  (apply str
         (for [[k label] [["aerial" "空中写真"] ["standard" "標準地図"] ["satellite" "衛星"]]]
           (if (basemap-ready? state k)
             (str "<button type=\"button\" data-map=\"basemap\" data-kind=\"" k "\">" label "</button>")
             ""))))

(defn- work-name-form [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (str "<form data-act=\"select-work-name\" method=\"post\">"
         "<label>" (esc (:work-name messages))
         "<input name=\"work_name\" list=\"work-name-list\" value=\"" (esc wn) "\">"
         "<datalist id=\"work-name-list\">"
         (apply str (for [nm (:work-names state)]
                      (str "<option value=\"" (esc nm) "\">")))
         "</datalist></label>"
         "<button type=\"submit\">" (esc (:work-name-see messages)) "</button></form>")))

(defn- paint-panel [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))
        fid (str/trim (str (or (get-in state [:form :field_id]) (get-in state [:form :id]) "")))
        pid (str/trim (str (or (get-in state [:form :paint-id]) "")))
        gj (str/trim (str (or (get-in state [:form :paint-geojson]) "")))]
    (str
     "<div class=\"paint-tools\" data-none=\"" (:none paint-colors)
     "\" data-partial=\"" (:partial paint-colors)
     "\" data-done=\"" (:done paint-colors) "\">"
     (work-name-form state)
     (when-not (str/blank? wn)
       (str
        "<p id=\"paint-legend\">"
        "<span>" (esc (:status-none messages)) "</span> "
        "<span>" (esc (:status-partial messages)) "</span> "
        "<span>" (esc (:status-done messages)) "</span></p>"
        "<div class=\"toolbar\">"
        "<button type=\"button\" data-map=\"brush\" data-hint=\"" (esc (:map-hint-brush messages)) "\">ブラシ</button>"
        "<button type=\"button\" data-map=\"discard\" data-hint=\"" (esc (:map-hint-brush messages)) "\">" (esc (:paint-discard messages)) "</button>"
        "</div>"
        "<form data-act=\"confirm-paint\" method=\"post\">"
        "<input type=\"hidden\" name=\"field_id\" value=\"" (esc fid) "\">"
        "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
        "<input type=\"hidden\" name=\"geojson\" value=\"" (esc gj) "\">"
        "<button type=\"submit\">" (esc (:paint-confirm messages)) "</button></form>"
        "<form data-act=\"complete-field\" method=\"post\">"
        "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
        "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
        "<button type=\"submit\">" (esc (:paint-complete messages)) "</button></form>"
        "<form data-act=\"delete-field-paints\" method=\"post\">"
        "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
        "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
        "<button type=\"submit\">" (esc (:paint-delete-all messages)) "</button></form>"
        "<form data-act=\"delete-paint\" method=\"post\">"
        "<input type=\"hidden\" name=\"id\" value=\"" (esc pid) "\">"
        "<button type=\"submit\">" (esc (:paint-delete messages)) "</button></form>"))
     "</div>")))

(defn- browse-panel [state]
  (str (work-name-form state)
       "<div class=\"toolbar\">"
       (mode-form "paint" (:map-do-paint messages))
       (mode-form "draw" "手描き")
       (mode-form "edit" "修正")
       (mode-form "split" "分割")
       (mode-form "merge" "合筆")
       (mode-form "import" (:map-do-import messages))
       (mode-form "basemap" (:map-do-basemap messages))
       (basemap-kind-buttons state)
       "</div>"))

(defn- draw-panel [state]
  (str (cancel-form)
       "<form data-act=\"create-field\" method=\"post\">"
       "<label>名前<input name=\"name\" required></label>"
       "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
       "<button type=\"submit\">圃場を保存</button></form>"))

(defn- edit-panel [state]
  (str (cancel-form)
       "<form data-act=\"update-field\" method=\"post\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (get-in state [:form :id] "")) "\">"
       "<label>名前<input name=\"name\" value=\"" (esc (get-in state [:form :name] "")) "\"></label>"
       "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
       "<button type=\"submit\">形と名前を保存</button></form>"))

(defn- split-panel [state]
  (str (cancel-form)
       "<form data-act=\"split-field\" method=\"post\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (or (get-in state [:form :split-id])
                                                              (get-in state [:form :id])
                                                              (get-in state [:form :field_id])
                                                              "")) "\">"
       "<input type=\"hidden\" name=\"polygons\" value=\"" (esc (get-in state [:form :polygons] "[]")) "\">"
       "<input type=\"hidden\" name=\"line\" value=\"" (esc (get-in state [:form :line] "")) "\">"
       "<button type=\"submit\">分割を保存</button></form>"))

(defn- merge-panel [state]
  (str (cancel-form)
       "<form data-act=\"merge-fields\" method=\"post\">"
       "<input type=\"hidden\" name=\"keep_id\" value=\"" (esc (get-in state [:form :keep_id] "")) "\">"
       "<input type=\"hidden\" name=\"ids\" value=\"" (esc (get-in state [:form :ids] "[]")) "\">"
       "<button type=\"submit\">合筆する</button></form>"))

(defn- import-panel []
  (str (cancel-form)
       "<form data-act=\"import-fields\" method=\"post\" enctype=\"multipart/form-data\">"
       "<label>区画ファイル<input name=\"file\" type=\"file\" accept=\".json,.geojson,application/geo+json\"></label>"
       "<button type=\"submit\">取り込む</button></form>"))

(defn- basemap-panel [state]
  (str (cancel-form)
       "<div class=\"toolbar\">" (basemap-kind-buttons state) "</div>"
       "<form data-act=\"emaff-import\" method=\"post\">"
       "<button type=\"submit\">" (esc (:emaff-import messages)) "</button></form>"
       "<form data-act=\"upload-basemap\" method=\"post\" enctype=\"multipart/form-data\">"
       "<label>下地"
       "<select name=\"kind\">"
       "<option value=\"aerial\">空中写真</option>"
       "<option value=\"standard\">標準地図</option>"
       "<option value=\"satellite\">衛星</option>"
       "</select></label>"
       "<input name=\"file\" type=\"file\" accept=\"image/jpeg,image/png,.jpg,.jpeg,.png\">"
       "<button type=\"submit\">下地を取り込む</button></form>"
       (when (some :ready (:basemaps state))
         (str "<p>" (esc (:map-hint-image messages)) "</p>"
              "<div class=\"toolbar\">"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"west\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-shift-west messages)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"east\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-shift-east messages)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"south\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-shift-south messages)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"north\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-shift-north messages)) "</button>"
              "<button type=\"button\" data-map=\"image-scale\" data-factor=\"0.94\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-scale-in messages)) "</button>"
              "<button type=\"button\" data-map=\"image-scale\" data-factor=\"1.06\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-scale-out messages)) "</button>"
              "<button type=\"button\" data-map=\"image-reset\" data-hint=\"" (esc (:map-hint-image messages)) "\">" (esc (:image-reset messages)) "</button>"
              "</div>"
              "<form data-act=\"save-image-extent\" method=\"post\">"
              "<input type=\"hidden\" name=\"west\" value=\"" (esc (str (or (:west (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"south\" value=\"" (esc (str (or (:south (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"east\" value=\"" (esc (str (or (:east (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"north\" value=\"" (esc (str (or (:north (image-bbox (:place state))) ""))) "\">"
              "<button type=\"submit\">" (esc (:image-save messages)) "</button></form>"))))

(defn- paint-mode-panel [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (str (paint-panel state)
         (when-not (str/blank? wn)
           (str "<div class=\"toolbar\">"
                (mode-form "browse" (:map-do-fields messages))
                (mode-form "basemap" (:map-do-basemap messages))
                "</div>"))
         (when (str/blank? wn)
           (cancel-form)))))

(defn map-place-view [state]
  (let [preview? (= "aerial" (str (:place-preview state)))
        busy? (boolean (:place-busy state))
        west (esc (get-in state [:form :west] "129"))
        south (esc (get-in state [:form :south] "26"))
        east (esc (get-in state [:form :east] "146"))
        north (esc (get-in state [:form :north] "46"))
        lead (cond
               busy? (:place-saving messages)
               (:place state) (:place-change messages)
               :else (:place-needed messages))
        guide (cond
                busy? ""
                preview? (:place-preview-note messages)
                :else (:place-move messages))
        disabled (if busy? " disabled" "")
        hidden (fn [act]
                 (str "<form data-act=\"" act "\" method=\"post\">"
                      "<input type=\"hidden\" name=\"west\" value=\"" west "\">"
                      "<input type=\"hidden\" name=\"south\" value=\"" south "\">"
                      "<input type=\"hidden\" name=\"east\" value=\"" east "\">"
                      "<input type=\"hidden\" name=\"north\" value=\"" north "\">"))]
    (layout (:map-title messages)
            (str (nav-user)
                 (flash-html state)
                 "<p>" (esc lead) "</p>"
                 (when-not (str/blank? guide) (str "<p>" (esc guide) "</p>"))
                 "<p class=\"attr\">" (esc (:place-gsi-attr messages)) "</p>"
                 "<p id=\"place-extent\"></p>"
                 (if busy?
                   ""
                   (if preview?
                     (str (hidden "cancel-place-preview")
                          "<button type=\"submit\"" disabled ">" (esc (:place-back-gsi messages)) "</button></form>"
                          (hidden "save-place")
                          "<button type=\"submit\"" disabled ">" (esc (:place-set messages)) "</button></form>")
                     (str (hidden "preview-place")
                          "<button type=\"submit\"" disabled ">" (esc (:place-preview messages)) "</button></form>"
                          (hidden "save-place")
                          "<button type=\"submit\"" disabled ">" (esc (:place-set messages)) "</button></form>")))
                 "<div id=\"ol-map\" class=\"ol-map\" data-place-mode=\"1\""
                 " data-west=\"" west "\" data-south=\"" south "\" data-east=\"" east "\" data-north=\"" north "\""
                 (when preview? " data-preview=\"aerial\"")
                 "></div>"))))

(defn map-view [state]
  (let [mode (map-mode state)]
    (layout (:map-title messages)
            (str (nav-user)
                 (flash-html state)
                 "<p><a data-nav href=\"/map/place\">作業場所を変える</a></p>"
                 "<p id=\"map-hint\">" (esc (map-hint-for mode)) "</p>"
                 "<p id=\"map-selection\"></p>"
                 (case mode
                   "browse" (browse-panel state)
                   "paint" (paint-mode-panel state)
                   "draw" (draw-panel state)
                   "edit" (edit-panel state)
                   "split" (split-panel state)
                   "merge" (merge-panel state)
                   "import" (import-panel)
                   "basemap" (basemap-panel state)
                   (browse-panel state))
                 "<div id=\"ol-map\" class=\"ol-map\"></div>"))))

(defn- pad2 [n]
  (let [s (str n)]
    (if (= 1 (count s)) (str "0" s) s)))

(defn- tokyo-ymd []
  #?(:clj
     (let [d (time/today-tokyo)]
       [(.getYear d) (.getMonthValue d) (.getDayOfMonth d)])
     :cljs
     (let [parts (.formatToParts
                  (js/Intl.DateTimeFormat. "en-US"
                                           #js {:timeZone "Asia/Tokyo"
                                                :year "numeric"
                                                :month "2-digit"
                                                :day "2-digit"})
                  (js/Date.))
           get (fn [t]
                 (some (fn [p]
                         (when (= t (.-type p)) (.-value p)))
                       (array-seq parts)))]
       [(js/parseInt (get "year") 10)
        (js/parseInt (get "month") 10)
        (js/parseInt (get "day") 10)])))

(defn- ymd-minute [y m d h mi]
  (str y "-" (pad2 m) "-" (pad2 d) "T" (pad2 h) ":" (pad2 mi)))

(defn- add-calendar-days [y m d days]
  #?(:clj
     (let [ld (.plusDays (java.time.LocalDate/of (int y) (int m) (int d)) (long days))]
       [(.getYear ld) (.getMonthValue ld) (.getDayOfMonth ld)])
     :cljs
     (let [ms (.getTime (js/Date. (str y "-" (pad2 m) "-" (pad2 d) "T12:00:00+09:00")))
           nd (js/Date. (+ ms (* days 24 60 60 1000)))
           parts (.formatToParts
                  (js/Intl.DateTimeFormat. "en-US"
                                           #js {:timeZone "Asia/Tokyo"
                                                :year "numeric"
                                                :month "2-digit"
                                                :day "2-digit"})
                  nd)
           get (fn [t]
                 (some (fn [p]
                         (when (= t (.-type p)) (.-value p)))
                       (array-seq parts)))]
       [(js/parseInt (get "year") 10)
        (js/parseInt (get "month") 10)
        (js/parseInt (get "day") 10)])))

(defn gantt-axis-bounds [axis]
  (let [[y m d] (tokyo-ymd)
        start (ymd-minute y m d 0 0)
        range-key (str (or axis "day"))]
    (case range-key
      "week"
      (let [[ey em ed] (add-calendar-days y m d 7)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "week"})
      "month"
      (let [ny (if (= m 12) (inc y) y)
            nm (if (= m 12) 1 (inc m))]
        {:start start :end (ymd-minute ny nm 1 0 0) :range "month"})
      (let [[ey em ed] (add-calendar-days y m d 3)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "day"}))))

(defn- as-text [v]
  (if (nil? v) "" (str v)))

(defn- same-gantt-id? [a b]
  (cond
    (nil? a) false
    (nil? b) false
    :else (= (str a) (str b))))

(defn- gantt-row-by-id [state id]
  (some (fn [r] (when (same-gantt-id? (:id r) id) r)) (:gantt-rows state)))

(defn- gantt-row-applicable? [row]
  (and row
       (seq (:field_ids row))
       (not (str/blank? (str/trim (as-text (:work_name row)))))))

(defn- form-field-ids [form]
  (let [v (:field_ids form)]
    (cond
      (nil? v) []
      (vector? v) (->> v (map str) (map str/trim) (remove str/blank?) vec)
      (sequential? v) (->> v (map str) (map str/trim) (remove str/blank?) vec)
      (str/blank? (str v)) []
      :else [(str/trim (str v))])))

(defn- gantt-body-from-form [form]
  (let [title (str/trim (as-text (:title form)))
        start (str/trim (as-text (:start_at form)))
        end (str/trim (as-text (:end_at form)))
        wn (str/trim (as-text (:work_name form)))
        fids (form-field-ids form)]
    (cond-> {:title title
             :start_at start
             :end_at end
             :field_ids fids}
      (not (str/blank? wn)) (assoc :work_name wn)
      (str/blank? wn) (assoc :work_name nil))))

(defn gantt-view [state]
  (let [fields (:fields state)]
    (layout (:gantt-title messages)
            (str (nav-user)
                 (flash-html state)
                 (if (empty? fields)
                   (str "<p>" (esc (:gantt-no-fields messages)) "</p>")
                   (let [axis (if (nil? (:gantt-axis state)) "day" (:gantt-axis state))
                         bounds (gantt-axis-bounds axis)
                         sel (gantt-row-by-id state (:gantt-selected state))
                         progress (:gantt-progress state)
                         applicable? (cond
                                        (nil? sel) false
                                        (not (gantt-row-applicable? sel)) false
                                        (nil? progress) false
                                        :else (boolean (:applicable progress)))
                         target-ids (when applicable? (:field_ids sel))
                         work-name (when applicable? (str (:work_name sel)))]
                     (str
                      "<div class=\"toolbar\">"
                      "<form data-act=\"set-gantt-axis\" method=\"post\" class=\"inline\">"
                      "<input type=\"hidden\" name=\"axis\" value=\"day\">"
                      "<button type=\"submit\">" (esc (:gantt-axis-day messages)) "</button></form>"
                      "<form data-act=\"set-gantt-axis\" method=\"post\" class=\"inline\">"
                      "<input type=\"hidden\" name=\"axis\" value=\"week\">"
                      "<button type=\"submit\">" (esc (:gantt-axis-week messages)) "</button></form>"
                      "<form data-act=\"set-gantt-axis\" method=\"post\" class=\"inline\">"
                      "<input type=\"hidden\" name=\"axis\" value=\"month\">"
                      "<button type=\"submit\">" (esc (:gantt-axis-month messages)) "</button></form>"
                      "</div>"
                      "<div id=\"gantt-axis\" class=\"gantt-axis\" data-start=\"" (esc (:start bounds))
                      "\" data-end=\"" (esc (:end bounds))
                      "\" data-range=\"" (esc (:range bounds)) "\">"
                      "<div id=\"gantt-ticks\" class=\"gantt-ticks\"></div>"
                      (apply str
                             (for [r (:gantt-rows state)]
                               (let [selected? (same-gantt-id? (:id r) (:gantt-selected state))]
                                 (str "<form class=\"gantt-row" (when selected? " selected") "\" data-act=\"select-gantt-row\" method=\"post\""
                                      " data-start=\"" (esc (:start_at r)) "\" data-end=\"" (esc (:end_at r)) "\""
                                      " data-id=\"" (esc (:id r)) "\">"
                                      "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                                      "<button type=\"submit\">" (esc (:title r))
                                      " (" (esc (:start_at r)) "〜" (esc (:end_at r)) ")</button>"
                                      "<div class=\"gantt-bar\"></div></form>"))))
                      "</div>"
                      "<form data-act=\"add-gantt-row\" method=\"post\" id=\"gantt-add-form\">"
                      "<label>" (esc (:gantt-title-label messages))
                      "<input id=\"gantt-new-title\" name=\"title\" placeholder=\"新しい予定\"></label>"
                      "<label>" (esc (:gantt-start messages))
                      "<input id=\"gantt-new-start\" name=\"start_at\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<label>" (esc (:gantt-end messages))
                      "<input id=\"gantt-new-end\" name=\"end_at\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<button type=\"submit\" id=\"gantt-add-btn\">" (esc (:gantt-add messages)) "</button></form>"
                      (when sel
                        (str
                         "<form data-act=\"save-gantt-row\" method=\"post\" id=\"gantt-save-form\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                         "<label>" (esc (:gantt-title-label messages))
                         "<input name=\"title\" value=\"" (esc (:title sel)) "\" required></label>"
                         "<label>" (esc (:gantt-start messages))
                         "<input name=\"start_at\" value=\"" (esc (:start_at sel)) "\" required></label>"
                         "<label>" (esc (:gantt-end messages))
                         "<input name=\"end_at\" value=\"" (esc (:end_at sel)) "\" required></label>"
                         "<label>" (esc (:work-name messages))
                         "<input name=\"work_name\" list=\"gantt-work-name-list\" value=\"" (esc (or (:work_name sel) "")) "\">"
                         "<datalist id=\"gantt-work-name-list\">"
                         (apply str (for [nm (:work-names state)]
                                      (str "<option value=\"" (esc nm) "\">")))
                         "</datalist></label>"
                         "<fieldset><legend>" (esc (:gantt-targets messages)) "</legend>"
                         (apply str
                                (for [f fields]
                                  (let [checked? (some #(same-gantt-id? % (:id f)) (:field_ids sel))]
                                    (str "<label><input type=\"checkbox\" name=\"field_ids\" value=\"" (esc (:id f)) "\""
                                         (when checked? " checked") "> "
                                         (esc (:name f)) "</label>"))))
                         "</fieldset>"
                         "<button type=\"submit\" id=\"gantt-save-btn\">保存</button></form>"))
                      "<div id=\"gantt-circle\" class=\"gantt-circle\""
                      (when applicable?
                        (str " data-percent=\"" (esc (:percent progress)) "\""))
                      "></div>"
                      "<div id=\"ol-map\" class=\"ol-map\" data-gantt-mode=\"1\""
                      (when applicable?
                        (str " data-target-ids=\"" (esc (str/join "," target-ids)) "\""
                             " data-work-name=\"" (esc work-name) "\""))
                      "></div>")))))))

(defn render [state]
  (if (and (:narrow? state) (contains? #{:fields :map :map-place :gantt :orders-new :others} (:page state)))
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
      :gantt (gantt-view state)
      :orders (orders-view state)
      :orders-new (orders-new-view state)
      :order (order-view state)
      :others (others-view state)
      :relations (relations-view state)
      (unknown-view))))

(defn apply-route [state path search]
  (let [r (route-for path)]
    (assoc state
           :path path
           :search (or search "")
           :page (:page r)
           :kind (:kind r)
           :order-id (:order-id r)
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

      (and (#{:map :map-place :gantt :order :others} (:page s)) (:session s)
           (or (not (:narrow? s)) (#{:order} (:page s))))
      {:state s :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}

      (and (= :fields (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (and (= :orders (:page s)) (:session s))
      {:state s :fx [[:api "GET" "/api/user/orders" nil :orders-loaded]
                     [:api "GET" "/api/user/fields" nil :home-fields-loaded]]}

      (and (= :orders-new (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (and (= :others (:page s)) (:session s) (:narrow? s))
      (guarded s)

      (= :home (:page s))
      (if (and (= "user" (:kind s)) (some? (:session s)))
        {:state s :fx [[:api "GET" "/api/user/fields" nil :home-fields-loaded]]}
        (guarded s))

      :else
      (guarded s))))

(defn- form-has-bbox? [form]
  (let [w (:west form) s (:south form) e (:east form) n (:north form)]
    (and (some? w) (some? s) (some? e) (some? n)
         (not (or (str/blank? (str w)) (str/blank? (str s))
                  (str/blank? (str e)) (str/blank? (str n)))))))

(defn- seed-place-form [state place]
  (if (and (= :map-place (:page state)) place (not (form-has-bbox? (:form state))))
    (merge (or (:form state) {})
           {:west (str (:west place))
            :south (str (:south place))
            :east (str (:east place))
            :north (str (:north place))})
    (:form state)))

(defn place-loaded [state body]
  (let [place (when (:ok body)
                (select-keys body [:west :south :east :north
                                   :image_west :image_south
                                   :image_east :image_north]))
        s (assoc state :place place :form (seed-place-form state place))]
    (cond
      (= :order (:page s))
      {:state s :fx [[:api "GET" (str "/api/user/orders/" (:order-id s)) nil :order-loaded]]}

      (= :others (:page s))
      {:state s :fx [[:api "GET" "/api/user/others/fields" nil :others-fields-loaded]]}

      :else
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]})))

(defn fields-loaded [state body]
  (let [s (assoc state :fields (or (:fields body) []))]
    (cond
      (and (= :gantt (:page s)) (empty? (:fields s)))
      (guarded s)

      (= :orders-new (:page s))
      (let [defaults #?(:clj {:work_date (time/today-work-date)
                              :start_time (time/order-default-start)
                              :end_time (time/order-default-end)
                              :work_name ""
                              :body ""
                              :recipient_emails ""
                              :field_ids []}
                        :cljs {:work_date ""
                               :start_time "08:00"
                               :end_time "17:00"
                               :work_name ""
                               :body ""
                               :recipient_emails ""
                               :field_ids []})
            s2 (assoc s :form (merge defaults (or (:form s) {})))]
        {:state s2 :fx [[:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})

      (#{:map :map-place :gantt} (:page s))
      {:state s :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]}

      :else
      (guarded s))))

(defn home-fields-loaded [state body]
  (guarded (assoc state :fields (or (:fields body) []))))

(defn basemaps-loaded [state body]
  (let [s (assoc state :basemaps (or (:basemaps body) []))]
    (cond
      (= :gantt (:page s))
      {:state s
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]}

      (#{:map :map-place} (:page s))
      {:state s :fx [[:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]}

      :else
      (guarded s))))

(defn work-names-loaded [state body]
  (let [s (assoc state :work-names (or (:work_names body) []))
        wn (str/trim (str (or (get-in s [:form :work_name]) "")))]
    (if (and (= :map (:page s)) (not (str/blank? wn)))
      {:state s :fx [[:api "GET" (paints-query wn) nil :paints-loaded]]}
      (guarded s))))

(defn paints-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :paint-data body))
    (let [s (assoc state :paint-data nil :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-loaded [state body]
  (if-not (:ok body)
    (let [s (assoc state :gantt-rows [] :gantt-selected nil :gantt-progress nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})
    (let [rows (if (nil? (:rows body)) [] (:rows body))
          sel (:gantt-selected state)
          sel' (when (and sel (some #(same-gantt-id? (:id %) sel) rows)) sel)
          row (when sel' (gantt-row-by-id (assoc state :gantt-rows rows) sel'))
          s (assoc state :gantt-rows rows :gantt-selected sel' :flash nil)]
      (if (gantt-row-applicable? row)
        {:state (assoc s :gantt-progress nil)
         :fx [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]}
        (guarded (assoc s :gantt-progress nil))))))

(defn gantt-save-result [state body]
  (if (:ok body)
    (let [row (:row body)
          id (:id row)]
      {:state (assoc state :gantt-selected id :flash nil)
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})
    (let [code (:code body)
          text (if (= "work_name_required" code)
                 (:gantt-work-needed messages)
                 (code-message code))
          s (assoc state :flash {:error? true :text text})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-progress-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :gantt-progress body))
    (let [s (assoc state :gantt-progress nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn orders-loaded [state body]
  (if (:ok body)
    (guarded (assoc state
                    :orders-sent (or (:sent body) [])
                    :orders-received (or (:received body) [])))
    (let [s (assoc state :orders-sent [] :orders-received []
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn order-loaded [state body]
  (if (:ok body)
    (let [s (assoc state :order (dissoc body :ok) :flash nil)]
      {:state s
       :fx [[:api "GET" (str "/api/user/orders/" (:id body) "/map") nil :order-map-loaded]]})
    (let [s (assoc state :order nil :order-map nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn order-map-loaded [state body]
  (if (:ok body)
    (let [s (assoc state :order-map (dissoc body :ok))]
      {:state s :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]})
    (guarded (assoc state :order-map nil))))

(defn order-save-result [state body]
  (if (:ok body)
    (let [id (:id body)]
      {:state (assoc state :order (dissoc body :ok) :flash nil)
       :fx [[:nav (str "/orders/" id)]]})
    (let [code (:code body)
          text (cond
                 (= "no_fields" code) (:order-no-fields messages)
                 (= "time_invalid" code) (:order-time-invalid messages)
                 (= "time_order" code) (:order-time-order messages)
                 :else (code-message code))
          s (assoc state :flash {:error? true :text text})]
      {:state s :fx [[:html (render s)]]})))

(defn journal-save-result [state body]
  (if (:ok body)
    {:state (assoc state :order (dissoc body :ok) :flash nil)
     :fx [[:api "GET" (str "/api/user/orders/" (:id body)) nil :order-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn others-fields-loaded [state body]
  (if (:ok body)
    (let [s (assoc state :others-fields (or (:fields body) []))]
      {:state s :fx [[:api "GET" "/api/user/others/work-names" nil :others-work-names-loaded]]})
    (let [s (assoc state :others-fields []
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn others-work-names-loaded [state body]
  (let [s (assoc state :others-work-names (or (:work_names body) []))]
    {:state s :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]}))

(defn others-paints-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :others-paint-data (dissoc body :ok) :flash nil))
    (let [s (assoc state :others-paint-data nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn relation-cut-result [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (:relations-cut-ok messages)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn- parse-recipient-emails [s]
  (->> (str/split (str (or s "")) #"[,\s]+")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- parse-field-ids-form [form]
  (let [v (:field_ids form)]
    (cond
      (sequential? v) (mapv str v)
      (nil? v) []
      (str/blank? (str v)) []
      :else [(str v)])))

(defn after-place-preview [state body]
  (if (:ok body)
    (let [box (or (:bbox body) {})
          form (-> (into {} (:form state))
                   (cond->
                     (:west box) (assoc :west (str (:west box)))
                     (:south box) (assoc :south (str (:south box)))
                     (:east box) (assoc :east (str (:east box)))
                     (:north box) (assoc :north (str (:north box))))
                   (dissoc :preview-note))
          s (assoc state :place-preview "aerial" :form form :flash nil :place-busy nil)]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :place-busy nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-place-save [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (:place-saving messages)}
                   :place-preview nil :place-busy true)]
      {:state s
       :fx [[:html (render s)]
            [:api "POST" "/api/user/emaff/import" {} :emaff-import-result]]})
    (let [s (assoc state :place-busy nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-emaff-import [state body]
  (let [ok? (:ok body)
        busy? (= "emaff_busy" (:code body))
        text (cond
               busy? (:emaff-busy messages)
               (and ok? (= "emaff_partial" (:code body))) (:emaff-partial messages)
               ok? (:emaff-import-ok messages)
               :else (code-message (:code body)))
        s (assoc state
                 :flash {:error? (not (or ok? busy?)) :text text}
                 :place-preview nil
                 :place-busy nil)]
    {:state s
     :fx (cond
           busy?
           [[:html (render s)]]

           (= :map (:page state))
           [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]
            [:api "GET" "/api/user/fields" nil :fields-loaded]]

           :else
           [[:nav "/map"]])}))

(defn after-field-save [state body]
  (if (:ok body)
    {:state (assoc state :flash {:error? false :text "保存しました"} :form {}
                   :map-mode "browse" :map-mode-parent nil)
     :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}
    (let [text (if (= "field_has_paint" (:code body))
                 (paint-block-text (:last-field-act state))
                 (code-message (:code body)))
          s (assoc state :flash {:error? true :text text})]
      {:state s :fx [[:html (render s)]]})))

(defn after-paint-save [state body]
  (if (:ok body)
    (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))
          fid (or (get-in state [:form :field_id]) (get-in state [:form :id]))
          form (cond-> (dissoc (:form state) :paint-geojson :paint-id)
                 (not (str/blank? wn)) (assoc :work_name wn)
                 fid (assoc :field_id (str fid) :id (str fid)))]
      {:state (assoc state :flash {:error? false :text (:paint-ok messages)} :form form
                     :map-mode "paint")
       :fx [[:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn- blank-work-name? [form state]
  (str/blank? (str/trim (str (or (:work_name form) (get-in state [:form :work_name]) "")))))

(defn- work-name-of [form state]
  (str/trim (str (or (:work_name form) (get-in state [:form :work_name]) ""))))

(defn- field-id-of [form state]
  (let [v (or (:field_id form)
              (:id form)
              (get-in state [:form :field_id])
              (get-in state [:form :id]))]
    (let [s (str/trim (str (or v "")))]
      (when-not (str/blank? s) s))))

(defn- paint-geojson-of [form state]
  (or (read-json-str (:geojson form))
      (read-json-str (get-in state [:form :paint-geojson]))))

(defn- paint-id-of [form state]
  (let [s (str/trim (str (or (:id form) (get-in state [:form :paint-id]) "")))]
    (when-not (str/blank? s) s)))

(defn- flash-html-state [state text]
  (let [s (assoc state :flash {:error? true :text text})]
    {:state s :fx [[:html (render s)]]}))

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

(defn after-image-save [state body]
  (if (:ok body)
    {:state (assoc state :flash {:error? false :text (:image-ok messages)})
     :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}
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
      :work-names-loaded (work-names-loaded state arg)
      :paints-loaded (paints-loaded state arg)
      :home-fields-loaded (home-fields-loaded state arg)
      :gantt-loaded (gantt-loaded state arg)
      :gantt-save-result (gantt-save-result state arg)
      :gantt-progress-loaded (gantt-progress-loaded state arg)
      :orders-loaded (orders-loaded state arg)
      :order-loaded (order-loaded state arg)
      :order-map-loaded (order-map-loaded state arg)
      :order-save-result (order-save-result state arg)
      :journal-save-result (journal-save-result state arg)
      :others-fields-loaded (others-fields-loaded state arg)
      :others-work-names-loaded (others-work-names-loaded state arg)
      :others-paints-loaded (others-paints-loaded state arg)
      :relation-cut-result (relation-cut-result state arg)
      :place-preview-result (after-place-preview state arg)
      :place-save-result (after-place-save state arg)
      :emaff-import-result (after-emaff-import state arg)
      :field-save-result (after-field-save state arg)
      :paint-save-result (after-paint-save state arg)
      :field-delete-result (after-field-delete state arg)
      :basemap-upload-result (after-basemap-upload state arg)
      :image-save-result (after-image-save state arg)
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
      (let [s (apply-route (assoc state :session (:session state) :flash nil) (:path arg) (:search arg))
            s (cond
                (#{:map :map-place} (:page s))
                (assoc s :form {} :paint-data nil :map-mode nil :map-mode-parent nil
                       :place-preview nil :place-busy nil)

                (= :gantt (:page s))
                (assoc s :gantt-selected nil :gantt-progress nil :gantt-axis "day"
                       :form {} :paint-data nil)

                (#{:orders :orders-new :order :others} (:page s))
                (assoc s :order nil :order-map nil :others-paint-data nil
                       :form {} :orders-sent [] :orders-received [] :others-fields [])

                :else s)]
        (if (and (:session s) (= (:kind s) (:kind state)))
          (session-loaded s {:ok true :email (get-in s [:session :email])})
          {:state (assoc s :session nil)
           :fx [[:session (:kind s)]]}))
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
          "preview-place"
          (if (:place-busy state)
            {:state state :fx [[:html (render state)]]}
            (let [s (assoc state :form (merge (or (:form state) {}) form) :flash nil)]
              {:state s :fx [[:api "POST" "/api/user/place/preview" form :place-preview-result]]}))
          "cancel-place-preview"
          (let [s (assoc state :place-preview nil :flash nil :place-busy nil
                         :form (merge (or (:form state) {}) (select-keys form [:west :south :east :north])))]
            {:state s :fx [[:html (render s)]]})
          "save-place"
          (if (:place-busy state)
            {:state state :fx [[:html (render state)]]}
            (let [s (assoc state :place-busy true
                           :form (merge (or (:form state) {}) form)
                           :flash {:error? false :text (:place-saving messages)})]
              {:state s
               :fx [[:html (render s)]
                    [:api "PUT" "/api/user/place" form :place-save-result]]}))
          "emaff-import"
          (if (:place-busy state)
            {:state state :fx [[:html (render state)]]}
            (let [s (assoc state :place-busy true
                           :flash {:error? false :text (:place-saving messages)})]
              {:state s
               :fx [[:html (render s)]
                    [:api "POST" "/api/user/emaff/import" {} :emaff-import-result]]}))
          "set-map-mode"
          (let [mode (str/trim (str (or (:mode form) "")))
                cur (map-mode state)
                parent (or (:map-mode-parent state) "browse")]
            (cond
              (= mode "cancel")
              (let [next (if (= cur "basemap") parent "browse")
                    s (assoc state :map-mode next :map-mode-parent nil :flash nil
                             :form (dissoc (:form state) :geojson :line :polygons :ids :keep_id :paint-geojson))]
                {:state s :fx [[:html (render s)]]})
              (= mode "basemap")
              (let [s (assoc state :map-mode "basemap" :map-mode-parent cur :flash nil)]
                {:state s :fx [[:html (render s)]]})
              (#{"browse" "paint" "draw" "edit" "split" "merge" "import"} mode)
              (let [s (assoc state :map-mode mode :flash nil)]
                {:state s :fx [[:html (render s)]]})
              :else
              {:state state :fx [[:html (render state)]]}))
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
          "split-field"
          (let [id (str (:id form))
                polys (let [v (read-json-str (:polygons form))]
                        (if (sequential? v) v []))
                line (read-json-str (:line form))]
            (cond
              (str/blank? id)
              (let [s (assoc state :flash {:error? true :text (code-message "field_not_found")})]
                {:state s :fx [[:html (render s)]]})
              (and (< (count polys) 2) (nil? line))
              (let [s (assoc state :flash {:error? true :text (code-message "split_too_few")})]
                {:state s :fx [[:html (render s)]]})
              :else
              {:state (assoc state :last-field-act "split")
               :fx [[:api "POST" (str "/api/user/fields/" id "/split")
                     (cond-> {:polygons polys}
                       line (assoc :line line))
                     :field-save-result]]}))
          "merge-fields"
          (let [ids (let [v (read-json-str (:ids form))]
                      (if (sequential? v) v []))]
            (if (< (count ids) 2)
              (let [s (assoc state :flash {:error? true :text (code-message "merge_too_few")})]
                {:state s :fx [[:html (render s)]]})
              {:state (assoc state :last-field-act "merge")
               :fx [[:api "POST" "/api/user/fields/merge"
                     {:keep_id (:keep_id form) :ids ids}
                     :field-save-result]]}))
          "select-work-name"
          (let [wn (str/trim (str (or (:work_name form) "")))]
            (if (str/blank? wn)
              (let [s (assoc state :form (assoc (:form state) :work_name "") :paint-data nil
                             :map-mode "paint"
                             :flash {:error? true :text (:work-name-needed messages)})]
                {:state s :fx [[:html (render s)]]})
              {:state (assoc state :form (assoc (:form state) :work_name wn) :flash nil
                             :map-mode "paint")
               :fx [[:api "GET" (paints-query wn) nil :paints-loaded]]}))
          "confirm-paint"
          (let [fid (field-id-of form state)
                wn (work-name-of form state)
                gj (paint-geojson-of form state)]
            (cond
              (blank-work-name? form state)
              (flash-html-state state (:work-name-needed messages))
              (nil? fid)
              (flash-html-state state (code-message "field_not_found"))
              (nil? gj)
              (flash-html-state state (code-message "paint_empty"))
              :else
              {:state (assoc state :form (assoc (:form state)
                                               :work_name wn
                                               :field_id fid
                                               :id fid
                                               :paint-geojson ""))
               :fx [[:api "POST" "/api/user/paints"
                     {:field_id fid
                      :work_name wn
                      :geojson gj}
                     :paint-save-result]]}))
          "complete-field"
          (let [fid (field-id-of form state)
                wn (work-name-of form state)]
            (cond
              (blank-work-name? form state)
              (flash-html-state state (:work-name-needed messages))
              (nil? fid)
              (flash-html-state state (code-message "field_not_found"))
              :else
              {:state (assoc state :form (assoc (:form state) :work_name wn :field_id fid :id fid))
               :fx [[:api "POST" (str "/api/user/fields/" fid "/complete")
                     {:work_name wn}
                     :paint-save-result]]}))
          "delete-paint"
          (if-let [pid (paint-id-of form state)]
            {:state state
             :fx [[:api "DELETE" (str "/api/user/paints/" pid) nil :paint-save-result]]}
            (flash-html-state state (code-message "paint_not_found")))
          "delete-field-paints"
          (let [fid (field-id-of form state)
                wn (work-name-of form state)]
            (cond
              (blank-work-name? form state)
              (flash-html-state state (:work-name-needed messages))
              (nil? fid)
              (flash-html-state state (code-message "field_not_found"))
              :else
              {:state (assoc state :form (assoc (:form state) :work_name wn :field_id fid :id fid))
               :fx [[:api "DELETE" (field-paints-query fid wn) nil :paint-save-result]]}))
          "discard-drafts"
          (let [s (assoc state
                         :form (dissoc (:form state) :paint-geojson)
                         :flash {:error? false :text (:paint-discard messages)})]
            {:state s :fx [[:html (render s)]]})
          "import-fields" {:state state :fx [[:upload "POST" "/api/user/fields/import" form :field-save-result]]}
          "save-image-extent" {:state state :fx [[:api "PUT" "/api/user/place/image" form :image-save-result]]}
          "upload-basemap" {:state state :fx [[:upload "PUT" (str "/api/user/basemaps/" (:kind form)) form :basemap-upload-result]]}
          "set-gantt-axis"
          (let [axis (str/trim (as-text (:axis form)))
                axis' (if (#{"day" "week" "month"} axis) axis "day")
                s (assoc state :gantt-axis axis' :flash nil)]
            {:state s :fx [[:html (render s)]]})
          "select-gantt-row"
          (let [id (str/trim (as-text (:id form)))
                row (gantt-row-by-id state id)
                s (assoc state :gantt-selected (when-not (str/blank? id) id) :flash nil)]
            (if (gantt-row-applicable? row)
              {:state (assoc s :gantt-progress nil)
               :fx [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]}
              (guarded (assoc s :gantt-progress nil))))
          "add-gantt-row"
          (let [title (str/trim (as-text (:title form)))
                title' (if (str/blank? title) "新しい予定" title)
                start (str/trim (as-text (:start_at form)))
                end (str/trim (as-text (:end_at form)))]
            (cond
              (or (str/blank? start) (str/blank? end))
              (flash-html-state state (:time-invalid messages))
              :else
              {:state state
               :fx [[:api "POST" "/api/user/gantt"
                     {:title title'
                      :start_at start
                      :end_at end
                      :work_name nil
                      :field_ids []}
                     :gantt-save-result]]}))
          "save-gantt-row"
          (let [id (str/trim (as-text (if (nil? (:id form)) (:gantt-selected state) (:id form))))
                body (gantt-body-from-form form)
                fids (:field_ids body)
                wn (str/trim (as-text (:work_name body)))]
            (cond
              (str/blank? id)
              (flash-html-state state (:gantt-not-found messages))
              (and (seq fids) (str/blank? wn))
              (flash-html-state state (:gantt-work-needed messages))
              (or (str/blank? (:start_at body)) (str/blank? (:end_at body)))
              (flash-html-state state (:time-invalid messages))
              :else
              {:state state
               :fx [[:api "PUT" (str "/api/user/gantt/" id) body :gantt-save-result]]}))
          "create-order"
          (let [emails (parse-recipient-emails (:recipient_emails form))
                fids (parse-field-ids-form form)
                body {:work_date (str/trim (as-text (:work_date form)))
                      :start_time (str/trim (as-text (:start_time form)))
                      :end_time (str/trim (as-text (:end_time form)))
                      :work_name (str/trim (as-text (:work_name form)))
                      :body (as-text (:body form))
                      :recipient_emails emails
                      :field_ids fids}
                s (assoc state :form (merge (or (:form state) {}) form
                                            {:recipient_emails (:recipient_emails form)
                                             :field_ids fids}))]
            {:state s
             :fx [[:api "POST" "/api/user/orders" body :order-save-result]]})
          "update-order"
          (let [id (str/trim (as-text (or (:id form) (:order-id state) (get-in state [:order :id]))))
                body {:work_date (str/trim (as-text (:work_date form)))
                      :start_time (str/trim (as-text (:start_time form)))
                      :end_time (str/trim (as-text (:end_time form)))
                      :body (as-text (:body form))}]
            (if (str/blank? id)
              (flash-html-state state (:order-not-found messages))
              {:state state
               :fx [[:api "PUT" (str "/api/user/orders/" id) body :order-save-result]]}))
          "close-order"
          (let [id (str/trim (as-text (or (:id form) (:order-id state) (get-in state [:order :id]))))]
            (if (str/blank? id)
              (flash-html-state state (:order-not-found messages))
              {:state state
               :fx [[:api "POST" (str "/api/user/orders/" id "/close") {} :order-save-result]]}))
          "post-journal"
          (let [id (str/trim (as-text (or (:id form) (:order-id state) (get-in state [:order :id]))))]
            (if (str/blank? id)
              (flash-html-state state (:order-not-found messages))
              {:state state
               :fx [[:api "POST" (str "/api/user/orders/" id "/journal")
                     {:body (:body form)}
                     :journal-save-result]]}))
          "select-others-work-name"
          (let [wn (str/trim (as-text (:work_name form)))]
            (if (str/blank? wn)
              (flash-html-state (assoc state :form (assoc (:form state) :work_name "")
                                       :others-paint-data nil)
                                (:work-name-needed messages))
              {:state (assoc state :form (assoc (:form state) :work_name wn) :flash nil)
               :fx [[:api "GET" (str "/api/user/others/paints?work_name=" (encode-q wn))
                     nil :others-paints-loaded]]}))
          "cut-relation"
          {:state state
           :fx [[:api "POST" "/api/admin/relations/cut" form :relation-cut-result]]}
          {:state state :fx [[:html (render state)]]}))
      {:state state :fx [[:html (render state)]]})))
