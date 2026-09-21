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
   :map-hint-paint "上から順に: ブラシで塗る → 塗りを確定する。全面完了や削除は地図で圃場／塗りを選んでから。形を直すときは「ほか」の圃場を直す"
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
   :map-section-paint "塗る"
   :map-section-field "選んだ圃場"
   :map-section-stroke "選んだ塗り"
   :map-section-other "ほか"
   :map-need-draft "先にブラシで下書きを書いてください"
   :map-need-field "先に地図で圃場をクリックしてください"
   :map-need-paint "先に地図で塗りをクリックしてください"
   :paint-ok "塗りを保存しました"
   :gantt-title "ガント"
   :phone-gantt "ガントの編集はパソコンで開いてください"
   :gantt-no-fields "圃場が1枚以上あるときだけ、ガントを使えます"
   :gantt-lead "題名ごとに作業を時間軸で見ます。棒を選ぶと編集・進捗％・地図を確認できます"
   :gantt-orphan-hint "題名のない作業はガントに出ません。作業画面で題名を付けるか、そのまま管理できます"
   :gantt-section-titles "1. 題名を選ぶ"
   :gantt-section-chart "2. 時間軸の作業"
   :gantt-section-add "この題名に作業を足す"
   :gantt-section-edit "選んだ作業を編集"
   :gantt-section-map "進捗と地図"
   :gantt-work-needed "対象圃場がある行は、作業名を入れてください"
   :gantt-time-order "終了は開始より後にしてください"
   :gantt-add "予定を足す"
   :gantt-title-label "作業タイトル"
   :gantt-title-hint "一覧やガントの棒に出る名前です。題名（グループ）や塗り用の作業名とは別です"
   :gantt-titles-label "題名"
   :gantt-title-add "題名を足す"
   :gantt-title-new-placeholder "新しい題名"
   :gantt-title-select "先に題名を選ぶか、下で題名を足してください"
   :gantt-title-deleted "題名を消しました"
   :gantt-title-delete-confirm "この題名と中の作業を一覧から消します。よろしいですか？"
   :gantt-work-add "作業を足す"
   :gantt-work-new-placeholder "新しい作業"
   :gantt-works-label "作業"
   :gantt-title-of-work "関連する題名"
   :gantt-title-none "（なし）"
   :works-title "作業"
   :works-lead "予定の追加・変更と実行状態の管理をします。日次一覧やガントからも同じ作業を扱えます"
   :works-section-list "登録済みの作業"
   :works-section-add "新しい作業を追加"
   :works-section-edit "選んだ作業を編集"
   :nav-works "作業"
   :nav-gantt "ガント"
   :phone-works "作業の編集はパソコンで開いてください"
   :works-no-fields "圃場が1枚以上あるときだけ、作業を管理できます"
   :works-empty "まだ作業がありません。下の「新しい作業を追加」から登録できます"
   :daily-title "日次一覧"
   :daily-lead "今日や今週の予定を、未着手／着手中／完了で回す一覧です"
   :nav-daily "日次"
   :phone-daily "日次一覧はパソコンで開いてください"
   :daily-no-fields "圃場が1枚以上あるときだけ、日次一覧を使えます"
   :daily-today "今日"
   :daily-week "今週"
   :daily-range-label "期間"
   :daily-filter-apply "絞り込む"
   :daily-filter-empty "状態フィルタを1つ以上オンにしてください"
   :daily-empty "該当する作業はありません"
   :daily-empty-filtered "選んだ期間・状態に重なる作業がありません。期間を変えるか、作業画面で開始・終了を確認してください"
   :daily-link-works "作業画面を開く"
   :daily-section-list "一覧"
   :daily-section-filter "期間と状態"
   :home-link-daily "今日・今週の予定を実行状態で回す"
   :home-link-works "予定の追加・編集"
   :home-link-gantt "題名ごとの時間軸・進捗・地図"
   :fields-lead "圃場の名前・面積・メモを直します。新しい圃場は地図画面で作ります"
   :invite-lead "相手のメールアドレスを入れると、初期パスワード付きで招待できます"
   :password-lead "今のパスワードを確認してから、新しいパスワードに換えます"
   :work-name-hint "地図の塗りや進捗％と結びつける名前です。無くてもかまいません"
   :execution-status "実行状態"
   :exec-not-started "未着手"
   :exec-in-progress "着手中"
   :exec-done "完了"
   :execution-status-invalid "実行状態が正しくありません"
   :range-invalid "期間の指定が正しくありません"
   :statuses-invalid "状態フィルタが正しくありません"
   :gantt-start "開始"
   :gantt-end "終了"
   :gantt-targets "対象圃場"
   :gantt-axis-day "日"
   :gantt-axis-week "週"
   :gantt-axis-weeks8 "8週"
   :gantt-axis-month "月"
   :gantt-axis-months3 "3ヶ月"
   :gantt-axis-months6 "6ヶ月"
   :gantt-axis-label "時間の範囲"
   :gantt-orient-label "向き"
   :gantt-orient-time-h "時刻を横"
   :gantt-orient-time-v "時刻を縦"
   :gantt-percent-unit "％"
   :gantt-dim-color "#e8e8e8"
   :gantt-delete "削除"
   :gantt-delete-confirm "この作業を一覧から消します。よろしいですか？"
   :gantt-deleted "作業を消しました"
   :gantt-review "振り返り"
   :gantt-review-empty "確定した日次％はまだありません"
   :gantt-progress-na "—"
   :gantt-day "日付"
   :title-required "題名を入れてください"
   :title-too-long "題名は200文字以内にしてください"
   :time-invalid "開始と終了は分までの日時にしてください"
   :gantt-not-found "その予定はありません"
   :nav-gantt-progress "進捗確定"
   :gantt-progress-title "進捗確定"
   :gantt-progress-day-label "日付（省略時は前日）"
   :gantt-progress-email-label "利用者メール（省略時は全員）"
   :gantt-progress-finalize "確定する"
   :gantt-progress-result "確定件数"
   :user-not-found "その利用者はいません"
   :orders-title "指示"
   :orders-create "指示を出す"
   :orders-close "この指示を閉じる"
   :orders-journal "日誌を書く"
   :others-title "他人の対象圃場"
   :relations-title "関係を切る"
   :order-no-fields "圃場が1枚以上あるときだけ、指示を出せます"
   :orders-new-lead "相手・日付・時間・作業名・対象圃場を入れて指示を出します"
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
   :order-time-order "終了は開始より後にしてください"
   :nav-home "ホーム"
   :nav-fields "圃場台帳"
   :nav-map "地図"
   :nav-invite "招待"
   :nav-password "パスワード"
   :nav-logout "ログアウト"
   :nav-users "取消し"
   :nav-relations "関係を切る"
   :label-email "メールアドレス"
   :label-password "パスワード"
   :label-current-password "今のパスワード"
   :label-new-password "新しいパスワード"
   :label-password-confirm "新しいパスワード（確認）"
   :btn-enter "入る"
   :btn-send-reset "案内を送る"
   :btn-set-password "決める"
   :btn-change-password "変える"
   :btn-invite "招待する"
   :btn-revoke "取り消す"
   :forgot-password "パスワードを忘れた"
   :user-gate "利用者入口"
   :admin-gate "管理者入口"
   :back-to-login "ログインへ"
   :reset-request-title "パスワード再設定の依頼"
   :reset-title "新しいパスワード"
   :invite-title "利用者を招待"
   :password-title "パスワード変更"
   :users-title "招待の取消し"
   :initial-password-label "初期パスワード"
   :brush "ブラシ"
   :name-label "名前"
   :memo-label "メモ"
   :unit-ha "ha"
   :unit-m2 "㎡"
   :lang-ja "日本語"
   :lang-en "English"
   :lang-label "言語"
   :map-mode-label "操作"
   :map-mode-choose "選ぶ"
   :basemap-kind-label "下地の種類"
   :email-a "メールアドレス A"
   :email-b "メールアドレス B"
   :btn-save-name "名前を保存"
   :btn-save-field-row "保存"
   :btn-delete "削除"
   :to-map "地図へ"
   :area-invalid "ha と ㎡ は0以上の数値にしてください"
   :memo-too-long "メモは2000文字以内にしてください"
   :basemap-aerial "空中写真"
   :basemap-standard "標準地図"
   :basemap-satellite "衛星"
   :map-draw "手描き"
   :map-edit "修正"
   :map-split "分割"
   :map-merge "合筆"
   :btn-save-field "圃場を保存"
   :btn-save-shape-name "形と名前を保存"
   :btn-save-split "分割を保存"
   :btn-do-merge "合筆する"
   :label-parcel-file "区画ファイル"
   :btn-import "取り込む"
   :btn-import-basemap "下地を取り込む"
   :label-basemap "下地"
   :change-place "作業場所を変える"
   :gantt-new-placeholder "新しい予定"
   :btn-save "保存"
   :saved-ok "保存しました"
   :basemap-uploaded "下地を取り込みました"
   :page-not-found "このページはありません。"
   :label-counterpart-email "相手のメールアドレス"
   :lang-invalid "言語の指定が正しくありません"})

(def messages-en
  {:user-login-title "User login"
   :admin-login-title "Admin login"
   :login-failed "Email or password is incorrect"
   :reset-requested "We sent instructions. If they do not arrive, you may not be invited, or you may be using the wrong entrance"
   :reset-invalid "This link cannot be used. Please try again"
   :invite-ok "Tell the other person the initial password. You cannot see the same string again after leaving this screen"
   :invite-duplicate-user "This email address is already invited as a user"
   :invite-duplicate-admin "This email address belongs to an admin and cannot be invited as a user"
   :invite-invalid "That is not a valid email address format"
   :user-home "You are signed in as a user"
   :admin-home "You are signed in as an admin. Admins do not own fields"
   :password-mismatch "The confirmation password does not match"
   :password-too-short "Password must be at least 8 characters"
   :password-wrong "The current password is incorrect"
   :password-ok "Password changed"
   :unauthorized "You are not signed in"
   :api-error "Could not communicate"
   :fields-title "Field ledger"
   :map-title "Map"
   :place-needed "Set the work area first"
   :place-change "You can change the current work area. Adjust the range on the GSI map, confirm with the aerial photo, then finalize"
   :place-move "Move the GSI map to set the range, confirm with the aerial photo, then finalize"
   :place-set "Use this range as the work area"
   :place-preview "Final check with aerial photo"
   :place-preview-note "This is a confirmation aerial photo. Finalize if the range is fine, or return to the GSI map to adjust"
   :place-gsi-attr "Map: Geospatial Information Authority of Japan"
   :place-back-gsi "Return to the GSI map to adjust the range"
   :place-saving "Saving the work area and importing basemaps. Please wait until it finishes"
   :emaff-import "Auto-import basemaps for this range"
   :emaff-import-ok "Auto-import of basemaps finished. Import parcels manually"
   :emaff-unavailable "Auto-import of basemaps failed. Use manual import"
   :emaff-partial "Only some basemaps were auto-imported. Import parcels manually"
   :emaff-busy "Basemaps are being imported now. Wait until it finishes before operating"
   :phone-map "Edit the ledger and map on a computer"
   :shape-not-area "Use a closed shape that has an area"
   :import-invalid "This file cannot be read as parcels"
   :forbidden "Not available at this entrance"
   :place-invalid "The work area range is not valid"
   :basemap-kind "Basemap kind is wrong"
   :basemap-missing "That basemap is not available yet"
   :field-not-found "That field does not exist"
   :split-too-few "Split into at least 2 pieces"
   :merge-too-few "Select at least 2 fields to merge"
   :merge-keep-missing "Include the field to keep among the targets"
   :map-hint "Only the buttons you need now are shown. Cancel returns to the menu"
   :map-hint-browse "Enter a work name, or choose paint, field shapes, or basemap"
   :map-hint-paint "Top to bottom: brush, then confirm paint. Complete or delete after selecting a field or paint on the map. To edit shapes, use Edit fields under Other"
   :map-hint-draw "Draw a closed shape, name it, and save the field"
   :map-hint-edit "Move vertices and save the shape and name"
   :map-hint-split "Click the field to split, then draw a line across it and save the split"
   :map-hint-merge "Click the field to keep, then click fields to merge, and press Merge"
   :map-hint-import "Choose a parcel file to import"
   :map-hint-image "Align the basemap to the field shapes and save the basemap position. All three kinds share the same position"
   :map-do-paint "Paint"
   :map-do-fields "Edit fields"
   :map-do-basemap "Basemap"
   :map-do-import "Import parcels"
   :map-cancel "Cancel"
   :image-shift-west "Shift basemap west"
   :image-shift-east "Shift basemap east"
   :image-shift-south "Shift basemap south"
   :image-shift-north "Shift basemap north"
   :image-scale-in "Shrink basemap"
   :image-scale-out "Enlarge basemap"
   :image-reset "Reset basemap to the work area range"
   :image-save "Save basemap position"
   :image-ok "Basemap position saved"
   :work-name "Work name"
   :work-name-needed "Enter a work name before painting"
   :work-name-too-long "Work name must be 100 characters or fewer"
   :work-name-see "View with this work name"
   :paint-confirm "Confirm paint"
   :paint-discard "Discard draft"
   :paint-complete "Mark this field fully done for this work name"
   :paint-delete "Delete this paint"
   :paint-delete-all "Delete all paint for this field and work name"
   :split-has-paint "A field with paint cannot be split. Delete the paint first"
   :merge-has-paint "A field with paint cannot be merged. Delete the paint first"
   :status-none "None"
   :status-partial "Partial"
   :status-done "Done"
   :paint-empty "There is nowhere to paint inside the field"
   :paint-not-found "That paint does not exist"
   :map-hint-brush "Press the brush, then stroke. Each stroke finishes, then you can drag the map"
   :map-section-paint "Paint"
   :map-section-field "Selected field"
   :map-section-stroke "Selected paint"
   :map-section-other "Other"
   :map-need-draft "Draw a draft with the brush first"
   :map-need-field "Click a field on the map first"
   :map-need-paint "Click a paint on the map first"
   :paint-ok "Paint saved"
   :gantt-title "Gantt"
   :phone-gantt "Edit the Gantt on a computer"
   :gantt-no-fields "Gantt is available only when you have at least one field"
   :gantt-lead "View works on a time axis under each title. Select a bar to edit, see progress %, and the map"
   :gantt-orphan-hint "Works without a title do not appear on the Gantt. Open Works to assign a title or manage them there"
   :gantt-section-titles "1. Choose a title"
   :gantt-section-chart "2. Works on the time axis"
   :gantt-section-add "Add a work under this title"
   :gantt-section-edit "Edit the selected work"
   :gantt-section-map "Progress and map"
   :gantt-work-needed "Rows with target fields need a work name"
   :gantt-time-order "End must be after start"
   :gantt-add "Add schedule"
   :gantt-title-label "Work title"
   :gantt-title-hint "Shown on lists and Gantt bars. Different from the group title and the paint work name"
   :gantt-titles-label "Title"
   :gantt-title-add "Add title"
   :gantt-title-new-placeholder "New title"
   :gantt-title-select "Select a title above, or add one below"
   :gantt-title-deleted "Title removed"
   :gantt-title-delete-confirm "Remove this title and its works from the list?"
   :gantt-work-add "Add work"
   :gantt-work-new-placeholder "New work"
   :gantt-works-label "Works"
   :gantt-title-of-work "Related title"
   :gantt-title-none "(none)"
   :works-title "Works"
   :works-lead "Add and edit schedules and execution status. The same works appear on Daily and Gantt"
   :works-section-list "Registered works"
   :works-section-add "Add a new work"
   :works-section-edit "Edit the selected work"
   :nav-works "Works"
   :nav-gantt "Gantt"
   :phone-works "Edit works on a computer"
   :works-no-fields "Work management is available only when you have at least one field"
   :works-empty "No works yet. Use “Add a new work” below"
   :daily-title "Daily list"
   :daily-lead "Run today’s or this week’s plans with Not started / In progress / Done"
   :nav-daily "Daily"
   :phone-daily "Open the daily list on a computer"
   :daily-no-fields "Daily list is available only when you have at least one field"
   :daily-today "Today"
   :daily-week "This week"
   :daily-range-label "Range"
   :daily-filter-apply "Apply filters"
   :daily-filter-empty "Turn on at least one status filter"
   :daily-empty "No matching works"
   :daily-empty-filtered "No works overlap the selected range and statuses. Change the range or check start/end on Works"
   :daily-link-works "Open Works"
   :daily-section-list "List"
   :daily-section-filter "Range and status"
   :home-link-daily "Run plans for today or this week by status"
   :home-link-works "Add and edit schedules"
   :home-link-gantt "Time axis, progress, and map by title"
   :fields-lead "Edit field names, areas, and memos. Create new fields on the map"
   :invite-lead "Enter the counterpart’s email to invite them with an initial password"
   :password-lead "Confirm your current password, then set a new one"
   :work-name-hint "Links to map paint and progress %. Optional"
   :execution-status "Execution status"
   :exec-not-started "Not started"
   :exec-in-progress "In progress"
   :exec-done "Done"
   :execution-status-invalid "Invalid execution status"
   :range-invalid "Invalid range"
   :statuses-invalid "Invalid status filter"
   :gantt-start "Start"
   :gantt-end "End"
   :gantt-targets "Target fields"
   :gantt-axis-day "Day"
   :gantt-axis-week "Week"
   :gantt-axis-weeks8 "8 weeks"
   :gantt-axis-month "Month"
   :gantt-axis-months3 "3 months"
   :gantt-axis-months6 "6 months"
   :gantt-axis-label "Time range"
   :gantt-orient-label "Layout"
   :gantt-orient-time-h "Time across"
   :gantt-orient-time-v "Time down"
   :gantt-percent-unit "%"
   :gantt-dim-color "#e8e8e8"
   :gantt-delete "Delete"
   :gantt-delete-confirm "Remove this work from the list?"
   :gantt-deleted "Work removed"
   :gantt-review "Review"
   :gantt-review-empty "No finalized daily percent yet"
   :gantt-progress-na "—"
   :gantt-day "Date"
   :title-required "Enter a title"
   :title-too-long "Title must be 200 characters or fewer"
   :time-invalid "Start and end must be date-times to the minute"
   :gantt-not-found "That schedule does not exist"
   :nav-gantt-progress "Finalize progress"
   :gantt-progress-title "Finalize progress"
   :gantt-progress-day-label "Day (yesterday if blank)"
   :gantt-progress-email-label "User email (all users if blank)"
   :gantt-progress-finalize "Finalize"
   :gantt-progress-result "Finalized count"
   :user-not-found "That user was not found"
   :orders-title "Orders"
   :orders-create "Create order"
   :orders-close "Close this order"
   :orders-journal "Write journal"
   :others-title "Others' target fields"
   :relations-title "Cut relation"
   :order-no-fields "Orders can be created only when you have at least one field"
   :orders-new-lead "Enter recipients, date, time, work name, and target fields to create an order"
   :recipient-not-user "Cannot send to a user with this email address"
   :recipient-self "You cannot include yourself as a recipient"
   :relation-busy "Cannot cut a relation while the two have an open order"
   :phone-orders-edit "Create, edit, and close orders on a computer"
   :phone-others "View others' target fields summary on a computer"
   :order-not-found "That order does not exist"
   :order-closed "This order is already closed"
   :order-not-issuer "Only the issuer can do this"
   :order-not-recipient "Only a recipient can write a journal"
   :journal-exists "You have already written a journal"
   :journal-required "Enter the journal body"
   :journal-too-long "Journal must be 2000 characters or fewer"
   :body-too-long "Order body must be 2000 characters or fewer"
   :recipients-required "Enter at least one recipient"
   :fields-required "Select at least one target field"
   :field-in-open-order "This operation is not allowed because the field is a target of an open order"
   :work-name-unrelated "That work name is not in a related order"
   :relation-not-found "Cannot cut those two users"
   :relation-idle "Not cutting because no counterpart fields are visible now"
   :order-open "Open"
   :order-closed-label "Closed"
   :order-work-date "Date"
   :order-start "Start"
   :order-end "End"
   :order-body "Request text"
   :order-recipients "Recipients"
   :order-fields "Target fields"
   :order-journals "Journals"
   :order-sent "Orders sent"
   :order-received "Orders received"
   :order-save "Save order"
   :order-update "Update request text and times"
   :relations-cut-ok "Relation cut"
   :order-time-invalid "Date must be YYYY-MM-DD and time HH:MM"
   :order-time-order "End must be after start"
   :nav-home "Home"
   :nav-fields "Field ledger"
   :nav-map "Map"
   :nav-invite "Invite"
   :nav-password "Password"
   :nav-logout "Log out"
   :nav-users "Revoke"
   :nav-relations "Cut relation"
   :label-email "Email"
   :label-password "Password"
   :label-current-password "Current password"
   :label-new-password "New password"
   :label-password-confirm "New password (confirm)"
   :btn-enter "Sign in"
   :btn-send-reset "Send instructions"
   :btn-set-password "Set"
   :btn-change-password "Change"
   :btn-invite "Invite"
   :btn-revoke "Revoke"
   :forgot-password "Forgot password"
   :user-gate "User entrance"
   :admin-gate "Admin entrance"
   :back-to-login "Back to login"
   :reset-request-title "Request password reset"
   :reset-title "New password"
   :invite-title "Invite a user"
   :password-title "Change password"
   :users-title "Revoke invite"
   :initial-password-label "Initial password"
   :brush "Brush"
   :name-label "Name"
   :memo-label "Memo"
   :unit-ha "ha"
   :unit-m2 "m²"
   :lang-ja "日本語"
   :lang-en "English"
   :lang-label "Language"
   :map-mode-label "Action"
   :map-mode-choose "Choose"
   :basemap-kind-label "Basemap kind"
   :email-a "Email A"
   :email-b "Email B"
   :btn-save-name "Save name"
   :btn-save-field-row "Save"
   :btn-delete "Delete"
   :to-map "To map"
   :area-invalid "ha and m² must be numbers 0 or greater"
   :memo-too-long "Memo must be 2000 characters or fewer"
   :basemap-aerial "Aerial photo"
   :basemap-standard "Standard map"
   :basemap-satellite "Satellite"
   :map-draw "Draw"
   :map-edit "Edit"
   :map-split "Split"
   :map-merge "Merge"
   :btn-save-field "Save field"
   :btn-save-shape-name "Save shape and name"
   :btn-save-split "Save split"
   :btn-do-merge "Merge"
   :label-parcel-file "Parcel file"
   :btn-import "Import"
   :btn-import-basemap "Import basemap"
   :label-basemap "Basemap"
   :change-place "Change work area"
   :gantt-new-placeholder "New schedule"
   :btn-save "Save"
   :saved-ok "Saved"
   :basemap-uploaded "Basemap imported"
   :page-not-found "This page does not exist."
   :label-counterpart-email "Counterpart email"
   :lang-invalid "Invalid language"})


(defn normalize-lang [lang]
  (if (= "en" (str lang)) "en" "ja"))

(defn ui-lang [state]
  (normalize-lang (:ui-lang state)))

(def ^:dynamic *msg* messages)

(defn- m [k]
  (get *msg* k))

(defn messages-for [lang]
  (if (= "en" (normalize-lang lang)) messages-en messages))

(defn with-ui-lang [state f]
  (binding [*msg* (messages-for (ui-lang state))]
    (f)))

(defn- pad2 [n]
  (let [s (str n)]
    (if (= 1 (count s)) (str "0" s) s)))

(defn- parse-int* [s]
  #?(:clj (Integer/parseInt (str s))
     :cljs (js/parseInt (str s) 10)))

(def ^:private month-names-en
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- parse-ymd [s]
  (when-let [mm (re-matches #"(\d{4})-(\d{2})-(\d{2})" (str s))]
    [(parse-int* (nth mm 1)) (parse-int* (nth mm 2)) (parse-int* (nth mm 3))]))

(defn- parse-hm [s]
  (when-let [mm (re-matches #"(\d{1,2}):(\d{2})" (str s))]
    [(parse-int* (nth mm 1)) (parse-int* (nth mm 2))]))

(defn- en-ui? []
  (identical? *msg* messages-en))

(defn format-display-date [s]
  (when-let [[y mo d] (parse-ymd s)]
    (if (en-ui?)
      (str d " " (nth month-names-en (dec mo)) " " y)
      (str y "年" mo "月" d "日"))))

(defn format-display-time [s]
  (when-let [[h mi] (parse-hm s)]
    (if (en-ui?)
      (str (pad2 h) ":" (pad2 mi))
      (str h ":" (pad2 mi)))))

(defn format-display-datetime
  ([date] (format-display-date date))
  ([date time]
   (let [d (format-display-date date)
         t (when-not (or (nil? time) (str/blank? (str time)))
             (format-display-time time))]
     (cond
       (and d t) (str d " " t)
       d d
       :else ""))))

(def paint-colors
  {:none "#c8c8c8"
   :partial "#e6b800"
   :done "#2e7d32"
   :dim "#e8e8e8"})

(defn code-message [code]
  (case code
    "login_failed" (m :login-failed)
    "reset_invalid" (m :reset-invalid)
    "invite_invalid_email" (m :invite-invalid)
    "invite_duplicate_user" (m :invite-duplicate-user)
    "invite_duplicate_admin" (m :invite-duplicate-admin)
    "password_mismatch" (m :password-mismatch)
    "password_too_short" (m :password-too-short)
    "password_wrong" (m :password-wrong)
    "unauthorized" (m :unauthorized)
    "forbidden" (m :forbidden)
    "place_unset" (m :place-needed)
    "place_invalid" (m :place-invalid)
    "emaff_unavailable" (m :emaff-unavailable)
    "emaff_partial" (m :emaff-partial)
    "emaff_busy" (m :emaff-busy)
    "emaff_empty" (m :emaff-unavailable)
    "shape_not_area" (m :shape-not-area)
    "area_invalid" (m :area-invalid)
    "memo_too_long" (m :memo-too-long)
    "basemap_kind" (m :basemap-kind)
    "basemap_missing" (m :basemap-missing)
    "field_not_found" (m :field-not-found)
    "split_too_few" (m :split-too-few)
    "merge_too_few" (m :merge-too-few)
    "merge_keep_missing" (m :merge-keep-missing)
    "import_invalid" (m :import-invalid)
    "work_name_required" (m :work-name-needed)
    "work_name_too_long" (m :work-name-too-long)
    "field_has_paint" (m :split-has-paint)
    "paint_not_found" (m :paint-not-found)
    "paint_empty" (m :paint-empty)
    "no_fields" (m :gantt-no-fields)
    "gantt_not_found" (m :gantt-not-found)
    "title_not_found" (m :gantt-title-select)
    "user_not_found" (m :user-not-found)
    "title_required" (m :title-required)
    "title_too_long" (m :title-too-long)
    "time_invalid" (m :time-invalid)
    "time_order" (m :gantt-time-order)
    "execution_status_invalid" (m :execution-status-invalid)
    "range_invalid" (m :range-invalid)
    "statuses_invalid" (m :statuses-invalid)
    "order_not_found" (m :order-not-found)
    "order_closed" (m :order-closed)
    "order_not_issuer" (m :order-not-issuer)
    "order_not_recipient" (m :order-not-recipient)
    "journal_exists" (m :journal-exists)
    "journal_required" (m :journal-required)
    "journal_too_long" (m :journal-too-long)
    "body_too_long" (m :body-too-long)
    "recipients_required" (m :recipients-required)
    "recipient_self" (m :recipient-self)
    "recipient_not_user" (m :recipient-not-user)
    "fields_required" (m :fields-required)
    "field_in_open_order" (m :field-in-open-order)
    "work_name_unrelated" (m :work-name-unrelated)
    "relation_busy" (m :relation-busy)
    "relation_not_found" (m :relation-not-found)
    "relation_idle" (m :relation-idle)
    "lang_invalid" (m :lang-invalid)
    (m :api-error)))

(defn encode-q [s]
  #?(:clj (java.net.URLEncoder/encode (str s) "UTF-8")
     :cljs (js/encodeURIComponent (str s))))

(defn paints-query [work-name]
  (str "/api/user/paints?work_name=" (encode-q work-name)))

(defn field-paints-query [id work-name]
  (str "/api/user/fields/" id "/paints?work_name=" (encode-q work-name)))

(defn paint-block-text [act]
  (if (= "merge" act)
    (m :merge-has-paint)
    (m :split-has-paint)))

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
          "/works" {:page :works :kind "user"}
          "/gantt" {:page :gantt :kind "user"}
          "/daily" {:page :daily :kind "user"}
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
          "/admin/gantt-progress" {:page :gantt-progress :kind "admin"}
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
  (contains? #{:home :invite :password :users :fields :map :map-place :works :gantt :daily
               :orders :orders-new :order :others :relations :gantt-progress} page))

(defn init-state []
  {:path "/"
   :search ""
   :page :login
   :kind "user"
   :ui-lang "ja"
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
   :gantt-titles []
   :gantt-title-selected nil
   :gantt-selected nil
   :gantt-progress nil
   :gantt-progress-days nil
   :gantt-axis "day"
   :gantt-orient "time-h"
   :gantt-finalize-result nil
   :daily-range "week"
   :daily-statuses ["not_started" "in_progress"]
   :daily-rows []
   :daily-total nil
   :orders-sent []
   :orders-received []
   :order nil
   :order-map nil
   :order-id nil
   :others-fields []
   :others-work-names []
   :others-paint-data nil})

(defn map-mode [state]
  (let [mm (:map-mode state)
        wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (cond
      (and mm (not (str/blank? (str mm)))) (str mm)
      (str/blank? wn) "browse"
      :else "paint")))

(defn- map-hint-for [mode]
  (case (str mode)
    "browse" (m :map-hint-browse)
    "paint" (m :map-hint-paint)
    "draw" (m :map-hint-draw)
    "edit" (m :map-hint-edit)
    "split" (m :map-hint-split)
    "merge" (m :map-hint-merge)
    "import" (m :map-hint-import)
    "basemap" (m :map-hint-image)
    (m :map-hint)))

(defn- mode-form [mode label]
  (str "<form data-act=\"set-map-mode\" method=\"post\" class=\"inline\">"
       "<input type=\"hidden\" name=\"mode\" value=\"" (esc mode) "\">"
       "<button type=\"submit\">" (esc label) "</button></form>"))

(defn- cancel-form []
  (mode-form "cancel" (m :map-cancel)))

(defn flash-html [state]
  (when-let [f (:flash state)]
    (str "<p class=\"flash " (if (:error? f) "error" "ok") "\">" (esc (:text f)) "</p>")))

(defn layout [title body]
  (str "<main><h1>" (esc title) "</h1>" body "</main>"))

(defn- select-switch [label data-select current options & [extra-attrs]]
  (str "<label class=\"select-switch\">" (esc label)
       "<select data-select=\"" (esc data-select) "\""
       (or extra-attrs "")
       " aria-label=\"" (esc label) "\">"
       (apply str
              (for [[value opt-label] options]
                (str "<option value=\"" (esc value) "\""
                     (when (= (str current) (str value)) " selected")
                     ">" (esc opt-label) "</option>")))
       "</select></label>"))

(defn- lang-switcher [state]
  (select-switch (m :lang-label) "lang" (ui-lang state)
                 [["ja" (m :lang-ja)]
                  ["en" (m :lang-en)]]))

(defn nav-user [state]
  (str "<nav>"
       (lang-switcher state)
       "<a data-nav href=\"/home\">" (esc (m :nav-home)) "</a>"
       "<a data-nav href=\"/daily\">" (esc (m :nav-daily)) "</a>"
       "<a data-nav href=\"/works\">" (esc (m :nav-works)) "</a>"
       "<a data-nav href=\"/gantt\">" (esc (m :nav-gantt)) "</a>"
       "<a data-nav href=\"/fields\">" (esc (m :nav-fields)) "</a>"
       "<a data-nav href=\"/map\">" (esc (m :nav-map)) "</a>"
       "<a data-nav href=\"/invite\">" (esc (m :nav-invite)) "</a>"
       "<a data-nav href=\"/password\">" (esc (m :nav-password)) "</a>"
       "<form data-act=\"logout\" method=\"post\"><button type=\"submit\">"
       (esc (m :nav-logout)) "</button></form></nav>"))

(defn nav-admin [state]
  (str "<nav>"
       (lang-switcher state)
       "<a data-nav href=\"/admin/home\">" (esc (m :nav-home)) "</a>"
       "<a data-nav href=\"/admin/invite\">" (esc (m :nav-invite)) "</a>"
       "<a data-nav href=\"/admin/users\">" (esc (m :nav-users)) "</a>"
       "<a data-nav href=\"/admin/relations\">" (esc (m :nav-relations)) "</a>"
       "<a data-nav href=\"/admin/gantt-progress\">" (esc (m :nav-gantt-progress)) "</a>"
       "<a data-nav href=\"/admin/password\">" (esc (m :nav-password)) "</a>"
       "<form data-act=\"logout\" method=\"post\"><button type=\"submit\">"
       (esc (m :nav-logout)) "</button></form></nav>"))

(defn login-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (m :admin-login-title) (m :user-login-title))
        reset (if admin? "/admin/reset/request" "/reset/request")
        other (if admin? ["/" (m :user-gate)] ["/admin" (m :admin-gate)])]
    (layout title
            (str (lang-switcher state)
                 (flash-html state)
                 "<form data-act=\"login\" method=\"post\">"
                 "<label>" (esc (m :label-email)) "<input name=\"email\" type=\"email\" required></label>"
                 "<label>" (esc (m :label-password)) "<input name=\"password\" type=\"password\" required></label>"
                 "<button type=\"submit\">" (esc (m :btn-enter)) "</button></form>"
                 "<p><a data-nav href=\"" reset "\">" (esc (m :forgot-password)) "</a></p>"
                 "<p><a data-nav href=\"" (first other) "\">" (esc (second other)) "</a></p>"))))

(defn reset-request-view [state]
  (layout (m :reset-request-title)
          (str (lang-switcher state)
               (flash-html state)
               "<form data-act=\"reset-request\" method=\"post\">"
               "<label>" (esc (m :label-email)) "<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">" (esc (m :btn-send-reset)) "</button></form>"
               "<p><a data-nav href=\"" (login-path (:kind state)) "\">" (esc (m :back-to-login)) "</a></p>")))

(defn reset-view [state]
  (layout (m :reset-title)
          (str (lang-switcher state)
               (flash-html state)
               "<form data-act=\"reset-complete\" method=\"post\">"
               "<label>" (esc (m :label-new-password)) "<input name=\"password\" type=\"password\" required></label>"
               "<label>" (esc (m :label-password-confirm)) "<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">" (esc (m :btn-set-password)) "</button></form>")))

(defn home-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (m :admin-home) (m :user-home))]
    (layout title
            (str (if admin? (nav-admin state) (nav-user state))
                 (flash-html state)
                 "<p>" (esc (get-in state [:session :email])) "</p>"
                 (when (and (not admin?) (seq (:fields state)))
                   (str "<p><a data-nav href=\"/daily\">" (esc (m :daily-title)) "</a>"
                        " — " (esc (m :home-link-daily)) "</p>"
                        "<p><a data-nav href=\"/works\">" (esc (m :works-title)) "</a>"
                        " — " (esc (m :home-link-works)) "</p>"
                        "<p><a data-nav href=\"/gantt\">" (esc (m :gantt-title)) "</a>"
                        " — " (esc (m :home-link-gantt)) "</p>"
                        "<p><a data-nav href=\"/orders/new\">" (esc (m :orders-create)) "</a></p>"))
                 (when (not admin?)
                   (str "<p><a data-nav href=\"/orders\">" (esc (m :orders-title)) "</a></p>"
                        "<p><a data-nav href=\"/others\">" (esc (m :others-title)) "</a></p>"))))))

(defn invite-view [state]
  (layout (m :invite-title)
          (str (if (= "admin" (:kind state)) (nav-admin state) (nav-user state))
               (flash-html state)
               "<p class=\"page-lead\">" (esc (m :invite-lead)) "</p>"
               (when-let [pw (:initial-password state)]
                 (str "<p>" (esc (m :invite-ok)) "</p><p>" (esc (m :initial-password-label))
                      ": <code>" (esc pw) "</code></p>"))
               "<section class=\"form-section\" id=\"invite-form-section\">"
               "<h2 class=\"section-title\">" (esc (m :invite-title)) "</h2>"
               "<form data-act=\"invite\" method=\"post\">"
               "<label>" (esc (m :label-counterpart-email)) "<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">" (esc (m :btn-invite)) "</button></form>"
               "</section>")))

(defn password-view [state]
  (layout (m :password-title)
          (str (if (= "admin" (:kind state)) (nav-admin state) (nav-user state))
               (flash-html state)
               "<p class=\"page-lead\">" (esc (m :password-lead)) "</p>"
               "<section class=\"form-section\" id=\"password-form-section\">"
               "<h2 class=\"section-title\">" (esc (m :password-title)) "</h2>"
               "<form data-act=\"password\" method=\"post\">"
               "<label>" (esc (m :label-current-password)) "<input name=\"current_password\" type=\"password\" required></label>"
               "<label>" (esc (m :label-new-password)) "<input name=\"password\" type=\"password\" required></label>"
               "<label>" (esc (m :label-password-confirm)) "<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">" (esc (m :btn-change-password)) "</button></form>"
               "</section>")))

(defn users-view [state]
  (layout (m :users-title)
          (str (nav-admin state)
               (flash-html state)
               "<ul>"
               (apply str
                      (for [u (:users state)]
                        (str "<li>" (esc (:email u))
                             "<form data-act=\"revoke\" method=\"post\">"
                             "<input type=\"hidden\" name=\"user_id\" value=\"" (esc (:id u)) "\">"
                             "<button type=\"submit\">" (esc (m :btn-revoke)) "</button></form></li>")))
               "</ul>")))

(defn unknown-view [state]
  (layout "ISAS"
          (str (lang-switcher state)
               "<p>" (esc (m :page-not-found)) "</p>"
               "<p><a data-nav href=\"/\">" (esc (m :user-gate)) "</a></p>")))

(defn phone-view [state]
  (cond
    (= :orders-new (:page state))
    (layout (m :orders-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-orders-edit)) "</p>"))

    (= :others (:page state))
    (layout (m :others-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-others)) "</p>"))

    (= :gantt (:page state))
    (layout (m :gantt-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-gantt)) "</p>"))

    (= :works (:page state))
    (layout (m :works-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-works)) "</p>"))

    (= :daily (:page state))
    (layout (m :daily-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-daily)) "</p>"))

    :else
    (layout (m :map-title)
            (str (nav-user state) (flash-html state) "<p>" (esc (m :phone-map)) "</p>"))))

(defn- order-status-label [status]
  (if (= "closed" status)
    (m :order-closed-label)
    (m :order-open)))

(defn- order-list-items [rows]
  (apply str
         (for [o rows]
           (str "<li><a data-nav href=\"/orders/" (esc (:id o)) "\">"
                (esc (or (format-display-date (:work_date o)) (:work_date o))) " "
                (esc (:work_name o)) "（" (esc (order-status-label (:status o))) "）"
                "</a></li>"))))

(defn orders-view [state]
  (layout (m :orders-title)
          (str (nav-user state)
               (flash-html state)
               (when (and (not (:narrow? state)) (seq (:fields state)))
                 (str "<p><a data-nav href=\"/orders/new\">" (esc (m :orders-create)) "</a></p>"))
               "<h2>" (esc (m :order-sent)) "</h2>"
               "<ul>" (order-list-items (:orders-sent state)) "</ul>"
               "<h2>" (esc (m :order-received)) "</h2>"
               "<ul>" (order-list-items (:orders-received state)) "</ul>")))

(defn orders-new-view [state]
  (if (empty? (:fields state))
    (layout (m :orders-title)
            (str (nav-user state) (flash-html state)
                 "<p>" (esc (m :order-no-fields)) "</p>"))
    (let [form (or (:form state) {})]
      (layout (m :orders-create)
              (str (nav-user state)
                   (flash-html state)
                   "<p class=\"page-lead\">" (esc (m :orders-new-lead)) "</p>"
                   "<section class=\"form-section\" id=\"orders-new-section\">"
                   "<h2 class=\"section-title\">" (esc (m :orders-create)) "</h2>"
                   "<form data-act=\"create-order\" method=\"post\">"
                   "<label>" (esc (m :order-work-date))
                   "<input name=\"work_date\" value=\"" (esc (:work_date form)) "\"></label>"
                   "<label>" (esc (m :order-start))
                   "<input name=\"start_time\" value=\"" (esc (:start_time form)) "\"></label>"
                   "<label>" (esc (m :order-end))
                   "<input name=\"end_time\" value=\"" (esc (:end_time form)) "\"></label>"
                   "<label>" (esc (m :work-name))
                   "<input name=\"work_name\" list=\"work-name-list\" value=\"" (esc (:work_name form)) "\">"
                   "<datalist id=\"work-name-list\">"
                   (apply str (for [n (:work-names state)]
                                (str "<option value=\"" (esc n) "\">")))
                   "</datalist>"
                   "<p class=\"field-hint\">" (esc (m :work-name-hint)) "</p></label>"
                   "<label>" (esc (m :order-body))
                   "<textarea name=\"body\">" (esc (:body form)) "</textarea></label>"
                   "<label>" (esc (m :order-recipients))
                   "<input name=\"recipient_emails\" value=\"" (esc (:recipient_emails form)) "\"></label>"
                   "<fieldset><legend>" (esc (m :order-fields)) "</legend>"
                   (apply str
                          (for [f (:fields state)]
                            (str "<label><input type=\"checkbox\" name=\"field_ids\" value=\"" (esc (:id f)) "\""
                                 (when (some #{(:id f) (str (:id f))} (or (:field_ids form) []))
                                   " checked")
                                 ">" (esc (:name f)) "</label>")))
                   "</fieldset>"
                   "<button type=\"submit\">" (esc (m :order-save)) "</button></form>"
                   "</section>")))))

(defn- order-map-html [state]
  (let [omap (:order-map state)
        fields (or (:fields omap) [])
        ids (mapv :id fields)
        wn (or (:work_name omap) (get-in state [:order :work_name]))]
    (when (seq fields)
      (str "<div id=\"ol-map\" class=\"ol-map\" data-order-mode=\"1\""
           " data-target-ids=\"" (esc (str/join "," ids)) "\""
           (when wn (str " data-work-name=\"" (esc wn) "\""))
           "></div>"))))

(defn- paint-status-label [st]
  (get {"none" (m :status-none)
        "partial" (m :status-partial)
        "done" (m :status-done)}
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
      (layout (m :orders-title)
              (str (nav-user state) (flash-html state)
                   "<p>" (esc (m :order-not-found)) "</p>"))
      (layout (m :orders-title)
              (str (nav-user state)
                   (flash-html state)
                   "<p>" (esc (or (format-display-date (:work_date o)) (:work_date o))) " "
                   (esc (or (format-display-time (:start_time o)) (:start_time o)))
                   "〜" (esc (or (format-display-time (:end_time o)) (:end_time o)))
                   " / " (esc (order-status-label (:status o))) "</p>"
                   "<p>" (esc (m :work-name)) ": " (esc (:work_name o)) "</p>"
                   "<p>" (esc (m :order-body)) ": " (esc (:body o)) "</p>"
                   "<p>" (esc (m :order-recipients)) ": "
                   (esc (str/join ", " (or (:recipient_emails o) []))) "</p>"
                   "<p>" (esc (m :order-fields)) ": "
                   (esc (str/join ", " field-labels)) "</p>"
                   (or (order-map-html state) "")
                   (when narrow?
                     (str "<p>" (esc (m :phone-orders-edit)) "</p>"))
                   (when can-edit?
                     (str "<form data-act=\"update-order\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<label>" (esc (m :order-work-date))
                          "<input name=\"work_date\" value=\"" (esc (:work_date o)) "\"></label>"
                          "<label>" (esc (m :order-start))
                          "<input name=\"start_time\" value=\"" (esc (:start_time o)) "\"></label>"
                          "<label>" (esc (m :order-end))
                          "<input name=\"end_time\" value=\"" (esc (:end_time o)) "\"></label>"
                          "<label>" (esc (m :order-body))
                          "<textarea name=\"body\">" (esc (:body o)) "</textarea></label>"
                          "<button type=\"submit\">" (esc (m :order-update)) "</button></form>"
                          "<form data-act=\"close-order\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<button type=\"submit\">" (esc (m :orders-close)) "</button></form>"))
                   "<h2>" (esc (m :order-journals)) "</h2>"
                   "<ul>"
                   (apply str
                          (for [j (:journals o)]
                            (str "<li>" (esc (:author_email j)) ": " (esc (:body j)) "</li>")))
                   "</ul>"
                   (when can-journal?
                     (str "<form data-act=\"post-journal\" method=\"post\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<label>" (esc (m :orders-journal))
                          "<textarea name=\"body\"></textarea></label>"
                          "<button type=\"submit\">" (esc (m :orders-journal)) "</button></form>")))))))

(defn others-view [state]
  (let [form (or (:form state) {})
        wn (str/trim (str (or (:work_name form) "")))
        paint (:others-paint-data state)]
    (layout (m :others-title)
            (str (nav-user state)
                 (flash-html state)
                 "<form data-act=\"select-others-work-name\" method=\"post\">"
                 "<label>" (esc (m :work-name))
                 "<input name=\"work_name\" list=\"others-work-list\" value=\"" (esc wn) "\">"
                 "<datalist id=\"others-work-list\">"
                 (apply str (for [n (:others-work-names state)]
                              (str "<option value=\"" (esc n) "\">")))
                 "</datalist></label>"
                 "<button type=\"submit\">" (esc (m :work-name-see)) "</button></form>"
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
                                 (when st (str "（" (esc (get {:none (m :status-none)
                                                                :partial (m :status-partial)
                                                                :done (m :status-done)}
                                                              (keyword st) st)) "）"))
                                 "</li>"))))
                 "</ul>"))))

(defn relations-view [state]
  (layout (m :relations-title)
          (str (nav-admin state)
               (flash-html state)
               "<form data-act=\"cut-relation\" method=\"post\">"
               "<label>" (esc (m :email-a)) "<input name=\"email_a\" type=\"email\" required></label>"
               "<label>" (esc (m :email-b)) "<input name=\"email_b\" type=\"email\" required></label>"
               "<button type=\"submit\">" (esc (m :relations-title)) "</button></form>")))

(defn gantt-progress-admin-view [state]
  (let [form (:form state)
        result (:gantt-finalize-result state)]
    (layout (m :gantt-progress-title)
            (str (nav-admin state)
                 (flash-html state)
                 "<form data-act=\"finalize-gantt-progress\" method=\"post\">"
                 "<label>" (esc (m :gantt-progress-day-label))
                 "<input name=\"day\" placeholder=\"YYYY-MM-DD\" value=\"" (esc (or (:day form) "")) "\"></label>"
                 "<label>" (esc (m :gantt-progress-email-label))
                 "<input name=\"email\" type=\"email\" value=\"" (esc (or (:email form) "")) "\"></label>"
                 "<button type=\"submit\">" (esc (m :gantt-progress-finalize)) "</button></form>"
                 (when result
                   (str "<p>" (esc (m :gantt-progress-result)) ": "
                        (esc (:finalized result))
                        (when (:day result)
                          (str " (" (esc (:day result)) ")"))
                        "</p>"))))))

(defn fields-view [state]
  (layout (m :fields-title)
          (str (nav-user state)
               (flash-html state)
               "<p class=\"page-lead\">" (esc (m :fields-lead)) "</p>"
               "<section class=\"form-section\" id=\"fields-list\">"
               "<h2 class=\"section-title\">" (esc (m :fields-title)) "</h2>"
               "<table><thead><tr><th>" (esc (m :name-label)) "</th><th>" (esc (m :unit-ha))
               "</th><th>" (esc (m :unit-m2)) "</th><th>" (esc (m :memo-label))
               "</th><th></th></tr></thead><tbody>"
               (apply str
                      (for [f (:fields state)]
                        (str "<tr><td colspan=\"5\"><form data-act=\"update-field\" method=\"post\" class=\"field-ledger-form\">"
                             "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id f)) "\">"
                             "<label>" (esc (m :name-label))
                             "<input name=\"name\" value=\"" (esc (:name f)) "\"></label>"
                             "<label>" (esc (m :unit-ha))
                             "<input name=\"area_ha\" inputmode=\"decimal\" value=\"" (esc (:area_ha f)) "\"></label>"
                             "<label>" (esc (m :unit-m2))
                             "<input name=\"area_m2\" inputmode=\"numeric\" value=\"" (esc (:area_m2 f)) "\"></label>"
                             "<label>" (esc (m :memo-label))
                             "<textarea name=\"memo\" rows=\"16\" cols=\"60\">"
                             (esc (or (:memo f) "")) "</textarea></label>"
                             "<button type=\"submit\">" (esc (m :btn-save-field-row)) "</button></form>"
                             "<form data-act=\"delete-field\" method=\"post\">"
                             "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id f)) "\">"
                             "<button type=\"submit\">" (esc (m :btn-delete)) "</button></form></td></tr>")))
               "</tbody></table>"
               "</section>"
               "<p><a data-nav href=\"/map\">" (esc (m :to-map)) "</a></p>")))
(defn- basemap-ready? [state kind]
  (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state))))

(defn- basemap-kind-label [kind]
  (case (str kind)
    "aerial" (m :basemap-aerial)
    "standard" (m :basemap-standard)
    (m :basemap-satellite)))

(defn- basemap-kind-select [state]
  (let [ready (vec (for [k ["aerial" "standard" "satellite"]
                         :when (basemap-ready? state k)]
                     [k (basemap-kind-label k)]))
        cur (or (:basemap-kind state) "aerial")]
    (when (seq ready)
      (select-switch (m :basemap-kind-label) "basemap-kind" cur ready
                     " data-map=\"basemap\""))))

(defn- map-mode-select [modes]
  (select-switch (m :map-mode-label) "map-mode" ""
                 (cons ["" (m :map-mode-choose)] modes)))

(defn- work-name-form [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (str "<form data-act=\"select-work-name\" method=\"post\">"
         "<label>" (esc (m :work-name))
         "<input name=\"work_name\" list=\"work-name-list\" value=\"" (esc wn) "\">"
         "<datalist id=\"work-name-list\">"
         (apply str (for [nm (:work-names state)]
                      (str "<option value=\"" (esc nm) "\">")))
         "</datalist></label>"
         "<button type=\"submit\">" (esc (m :work-name-see)) "</button></form>")))

(defn- map-action-section [title body]
  (str "<section class=\"map-actions\">"
       "<h3 class=\"map-actions-title\">" (esc title) "</h3>"
       body
       "</section>"))

(defn- paint-panel [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))
        fid (str/trim (str (or (get-in state [:form :field_id]) (get-in state [:form :id]) "")))
        pid (str/trim (str (or (get-in state [:form :paint-id]) "")))
        gj (str/trim (str (or (get-in state [:form :paint-geojson]) "")))
        has-draft? (not (str/blank? gj))
        has-field? (not (str/blank? fid))
        has-paint? (not (str/blank? pid))
        dis (fn [ok? tip]
              (str (when-not ok? " disabled")
                   " title=\"" (esc (if ok? "" tip)) "\""))]
    (str
     "<div class=\"paint-tools\" data-none=\"" (:none paint-colors)
     "\" data-partial=\"" (:partial paint-colors)
     "\" data-done=\"" (:done paint-colors) "\">"
     (work-name-form state)
     (when-not (str/blank? wn)
       (str
        "<p id=\"paint-legend\">"
        "<span>" (esc (m :status-none)) "</span> "
        "<span>" (esc (m :status-partial)) "</span> "
        "<span>" (esc (m :status-done)) "</span></p>"
        (map-action-section
         (m :map-section-paint)
         (str "<div class=\"toolbar paint-flow\">"
              "<button type=\"button\" id=\"paint-brush-btn\" data-map=\"brush\" data-hint=\""
              (esc (m :map-hint-brush)) "\">" (esc (m :brush)) "</button>"
              "<form data-act=\"confirm-paint\" method=\"post\" class=\"inline\">"
              "<input type=\"hidden\" name=\"field_id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<input type=\"hidden\" name=\"geojson\" value=\"" (esc gj) "\">"
              "<button type=\"submit\" id=\"paint-confirm-btn\" data-need=\"draft\""
              (dis has-draft? (m :map-need-draft)) ">"
              (esc (m :paint-confirm)) "</button></form>"
              "<button type=\"button\" id=\"paint-discard-btn\" data-map=\"discard\" data-need=\"draft\""
              (dis has-draft? (m :map-need-draft))
              " data-hint=\"" (esc (m :map-hint-brush)) "\">"
              (esc (m :paint-discard)) "</button>"
              "</div>"))
        (map-action-section
         (m :map-section-field)
         (str "<p class=\"map-actions-note\" id=\"paint-field-note\""
              (when has-field? " hidden") ">"
              (esc (m :map-need-field)) "</p>"
              "<form data-act=\"complete-field\" method=\"post\" class=\"inline\">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<button type=\"submit\" id=\"paint-complete-btn\" data-need=\"field\""
              (dis has-field? (m :map-need-field)) ">"
              (esc (m :paint-complete)) "</button></form>"
              "<form data-act=\"delete-field-paints\" method=\"post\" class=\"inline\">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<button type=\"submit\" id=\"paint-delete-all-btn\" data-need=\"field\""
              (dis has-field? (m :map-need-field)) ">"
              (esc (m :paint-delete-all)) "</button></form>"))
        (map-action-section
         (m :map-section-stroke)
         (str "<p class=\"map-actions-note\" id=\"paint-stroke-note\""
              (when has-paint? " hidden") ">"
              (esc (m :map-need-paint)) "</p>"
              "<form data-act=\"delete-paint\" method=\"post\" class=\"inline\">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc pid) "\">"
              "<button type=\"submit\" id=\"paint-delete-btn\" data-need=\"paint\""
              (dis has-paint? (m :map-need-paint)) ">"
              (esc (m :paint-delete)) "</button></form>"))))
     "</div>")))

(defn- browse-panel [state]
  (str (work-name-form state)
       "<div class=\"toolbar\">"
       (map-mode-select [["paint" (m :map-do-paint)]
                         ["draw" (m :map-draw)]
                         ["edit" (m :map-edit)]
                         ["split" (m :map-split)]
                         ["merge" (m :map-merge)]
                         ["import" (m :map-do-import)]
                         ["basemap" (m :map-do-basemap)]])
       (or (basemap-kind-select state) "")
       "</div>"))

(defn- draw-panel [state]
  (str (cancel-form)
       "<form data-act=\"create-field\" method=\"post\">"
       "<label>" (esc (m :name-label)) "<input name=\"name\" required></label>"
       "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
       "<button type=\"submit\">" (esc (m :btn-save-field)) "</button></form>"))

(defn- edit-panel [state]
  (str (cancel-form)
       "<form data-act=\"update-field\" method=\"post\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (get-in state [:form :id] "")) "\">"
       "<label>" (esc (m :name-label)) "<input name=\"name\" value=\"" (esc (get-in state [:form :name] "")) "\"></label>"
       "<input type=\"hidden\" name=\"geojson\" value=\"" (esc (get-in state [:form :geojson] "")) "\">"
       "<button type=\"submit\">" (esc (m :btn-save-shape-name)) "</button></form>"))

(defn- split-panel [state]
  (str (cancel-form)
       "<form data-act=\"split-field\" method=\"post\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (or (get-in state [:form :split-id])
                                                              (get-in state [:form :id])
                                                              (get-in state [:form :field_id])
                                                              "")) "\">"
       "<input type=\"hidden\" name=\"polygons\" value=\"" (esc (get-in state [:form :polygons] "[]")) "\">"
       "<input type=\"hidden\" name=\"line\" value=\"" (esc (get-in state [:form :line] "")) "\">"
       "<button type=\"submit\">" (esc (m :btn-save-split)) "</button></form>"))

(defn- merge-panel [state]
  (str (cancel-form)
       "<form data-act=\"merge-fields\" method=\"post\">"
       "<input type=\"hidden\" name=\"keep_id\" value=\"" (esc (get-in state [:form :keep_id] "")) "\">"
       "<input type=\"hidden\" name=\"ids\" value=\"" (esc (get-in state [:form :ids] "[]")) "\">"
       "<button type=\"submit\">" (esc (m :btn-do-merge)) "</button></form>"))

(defn- import-panel []
  (str (cancel-form)
       "<form data-act=\"import-fields\" method=\"post\" enctype=\"multipart/form-data\">"
       "<label>" (esc (m :label-parcel-file)) "<input name=\"file\" type=\"file\" accept=\".json,.geojson,application/geo+json\"></label>"
       "<button type=\"submit\">" (esc (m :btn-import)) "</button></form>"))

(defn- basemap-panel [state]
  (str (cancel-form)
       "<div class=\"toolbar\">" (or (basemap-kind-select state) "") "</div>"
       "<form data-act=\"emaff-import\" method=\"post\">"
       "<button type=\"submit\">" (esc (m :emaff-import)) "</button></form>"
       "<form data-act=\"upload-basemap\" method=\"post\" enctype=\"multipart/form-data\">"
       "<label>" (esc (m :label-basemap))
       "<select name=\"kind\">"
       "<option value=\"aerial\">" (esc (m :basemap-aerial)) "</option>"
       "<option value=\"standard\">" (esc (m :basemap-standard)) "</option>"
       "<option value=\"satellite\">" (esc (m :basemap-satellite)) "</option>"
       "</select></label>"
       "<input name=\"file\" type=\"file\" accept=\"image/jpeg,image/png,.jpg,.jpeg,.png\">"
       "<button type=\"submit\">" (esc (m :btn-import-basemap)) "</button></form>"
       (when (some :ready (:basemaps state))
         (str "<p>" (esc (m :map-hint-image)) "</p>"
              "<div class=\"toolbar\">"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"west\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-shift-west)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"east\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-shift-east)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"south\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-shift-south)) "</button>"
              "<button type=\"button\" data-map=\"image-shift\" data-dir=\"north\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-shift-north)) "</button>"
              "<button type=\"button\" data-map=\"image-scale\" data-factor=\"0.94\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-scale-in)) "</button>"
              "<button type=\"button\" data-map=\"image-scale\" data-factor=\"1.06\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-scale-out)) "</button>"
              "<button type=\"button\" data-map=\"image-reset\" data-hint=\"" (esc (m :map-hint-image)) "\">" (esc (m :image-reset)) "</button>"
              "</div>"
              "<form data-act=\"save-image-extent\" method=\"post\">"
              "<input type=\"hidden\" name=\"west\" value=\"" (esc (str (or (:west (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"south\" value=\"" (esc (str (or (:south (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"east\" value=\"" (esc (str (or (:east (image-bbox (:place state))) ""))) "\">"
              "<input type=\"hidden\" name=\"north\" value=\"" (esc (str (or (:north (image-bbox (:place state))) ""))) "\">"
              "<button type=\"submit\">" (esc (m :image-save)) "</button></form>"))))

(defn- paint-mode-panel [state]
  (let [wn (str/trim (str (or (get-in state [:form :work_name]) "")))]
    (str (paint-panel state)
         (when-not (str/blank? wn)
           (map-action-section
            (m :map-section-other)
            (str "<div class=\"toolbar\">"
                 (map-mode-select [["browse" (m :map-do-fields)]
                                   ["basemap" (m :map-do-basemap)]])
                 "</div>")))
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
               busy? (m :place-saving)
               (:place state) (m :place-change)
               :else (m :place-needed))
        guide (cond
                busy? ""
                preview? (m :place-preview-note)
                :else (m :place-move))
        disabled (if busy? " disabled" "")
        hidden (fn [act]
                 (str "<form data-act=\"" act "\" method=\"post\">"
                      "<input type=\"hidden\" name=\"west\" value=\"" west "\">"
                      "<input type=\"hidden\" name=\"south\" value=\"" south "\">"
                      "<input type=\"hidden\" name=\"east\" value=\"" east "\">"
                      "<input type=\"hidden\" name=\"north\" value=\"" north "\">"))]
    (layout (m :map-title)
            (str (nav-user state)
                 (flash-html state)
                 "<p>" (esc lead) "</p>"
                 (when-not (str/blank? guide) (str "<p>" (esc guide) "</p>"))
                 "<p class=\"attr\">" (esc (m :place-gsi-attr)) "</p>"
                 "<p id=\"place-extent\"></p>"
                 (if busy?
                   ""
                   (if preview?
                     (str (hidden "cancel-place-preview")
                          "<button type=\"submit\"" disabled ">" (esc (m :place-back-gsi)) "</button></form>"
                          (hidden "save-place")
                          "<button type=\"submit\"" disabled ">" (esc (m :place-set)) "</button></form>")
                     (str (hidden "preview-place")
                          "<button type=\"submit\"" disabled ">" (esc (m :place-preview)) "</button></form>"
                          (hidden "save-place")
                          "<button type=\"submit\"" disabled ">" (esc (m :place-set)) "</button></form>")))
                 "<div id=\"ol-map\" class=\"ol-map\" data-place-mode=\"1\""
                 " data-west=\"" west "\" data-south=\"" south "\" data-east=\"" east "\" data-north=\"" north "\""
                 (when preview? " data-preview=\"aerial\"")
                 "></div>"))))

(defn map-view [state]
  (let [mode (map-mode state)]
    (layout (m :map-title)
            (str (nav-user state)
                 (flash-html state)
                 "<p><a data-nav href=\"/map/place\">" (esc (m :change-place)) "</a></p>"
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

(defn- add-calendar-months [y m d months]
  #?(:clj
     (let [ld (.plusMonths (java.time.LocalDate/of (int y) (int m) (int d)) (long months))]
       [(.getYear ld) (.getMonthValue ld) (.getDayOfMonth ld)])
     :cljs
     (let [total (+ (dec (int m)) (long months))
           ny (+ (int y) (js/Math.floor (/ total 12)))
           nm (inc (mod total 12))
           leap? (or (zero? (mod ny 400))
                     (and (zero? (mod ny 4)) (not (zero? (mod ny 100)))))
           dim (case nm
                 2 (if leap? 29 28)
                 (4 6 9 11) 30
                 31)
           nd (min (int d) dim)]
       [ny nm nd])))

(defn gantt-axis-bounds [axis]
  (let [[y m d] (tokyo-ymd)
        start (ymd-minute y m d 0 0)
        range-key (str (or axis "day"))]
    (case range-key
      "week"
      (let [[ey em ed] (add-calendar-days y m d 7)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "week"})
      "weeks8"
      (let [[ey em ed] (add-calendar-days y m d 56)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "weeks8"})
      "month"
      (let [ny (if (= m 12) (inc y) y)
            nm (if (= m 12) 1 (inc m))]
        {:start start :end (ymd-minute ny nm 1 0 0) :range "month"})
      "months3"
      (let [[ey em ed] (add-calendar-months y m d 3)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "months3"})
      "months6"
      (let [[ey em ed] (add-calendar-months y m d 6)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "months6"})
      (let [[ey em ed] (add-calendar-days y m d 3)]
        {:start start :end (ymd-minute ey em ed 0 0) :range "day"}))))

(defn- normalize-gantt-orient [v]
  (if (= "time-v" (str v)) "time-v" "time-h"))

(defn- as-text [v]
  (if (nil? v) "" (str v)))

(defn- gantt-new-defaults []
  (let [[yy mm dd] (tokyo-ymd)]
    {:title (m :gantt-work-new-placeholder)
     :start (ymd-minute yy mm dd 8 0)
     :end (ymd-minute yy mm dd 17 0)}))

(defn- field-hint [text]
  (str "<p class=\"field-hint\">" (esc text) "</p>"))

(defn- same-gantt-id? [a b]
  (cond
    (nil? a) false
    (nil? b) false
    :else (= (str a) (str b))))

(defn- gantt-row-by-id [state id]
  (some (fn [r] (when (same-gantt-id? (:id r) id) r)) (:gantt-rows state)))

(defn- gantt-title-by-id [state id]
  (some (fn [t] (when (same-gantt-id? (:id t) id) t)) (:gantt-titles state)))

(defn- gantt-rows-for-title [state title-id]
  (if (nil? title-id)
    []
    (filterv #(same-gantt-id? (:title_id %) title-id) (:gantt-rows state))))

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

(defn- form-status-list [form]
  (let [v (:status form)]
    (cond
      (nil? v) []
      (vector? v) (->> v (map str) (map str/trim) (remove str/blank?) vec)
      (sequential? v) (->> v (map str) (map str/trim) (remove str/blank?) vec)
      (str/blank? (str v)) []
      :else [(str/trim (str v))])))

(defn- daily-row-put-body [row status]
  {:title (str (:title row))
   :title_id (:title_id row)
   :start_at (:start_at row)
   :end_at (:end_at row)
   :work_name (:work_name row)
   :field_ids (or (:field_ids row) [])
   :execution_status status})

(defn- gantt-body-from-form [form title-id]
  (let [title (str/trim (as-text (:title form)))
        start (str/trim (as-text (:start_at form)))
        end (str/trim (as-text (:end_at form)))
        wn (str/trim (as-text (:work_name form)))
        st (str/trim (as-text (:execution_status form)))
        fids (form-field-ids form)
        tid (str/trim (as-text (if (nil? (:title_id form)) title-id (:title_id form))))]
    (cond-> {:title title
             :start_at start
             :end_at end
             :field_ids fids
             :title_id tid}
      (not (str/blank? wn)) (assoc :work_name wn)
      (str/blank? wn) (assoc :work_name nil)
      (not (str/blank? st)) (assoc :execution_status st))))

(defn- execution-status-label [status]
  (case (str status)
    "in_progress" (m :exec-in-progress)
    "done" (m :exec-done)
    (m :exec-not-started)))

(defn- execution-status-select-html [selected select-id]
  (let [cur (let [s (str (or selected "not_started"))]
              (if (#{"not_started" "in_progress" "done"} s) s "not_started"))]
    (str "<select name=\"execution_status\""
         (when-not (str/blank? (str select-id))
           (str " id=\"" (esc select-id) "\""))
         ">"
         (apply str
                (for [[v lab] [["not_started" (m :exec-not-started)]
                               ["in_progress" (m :exec-in-progress)]
                               ["done" (m :exec-done)]]]
                  (str "<option value=\"" v "\""
                       (when (= v cur) " selected")
                       ">" (esc lab) "</option>")))
         "</select>")))

(defn- daily-status-on? [state status]
  (boolean (some #(= (str %) (str status)) (into [] (:daily-statuses state)))))

(defn- daily-query-path [state]
  (let [range (let [raw (:daily-range state)
                    r (str (if (nil? raw) "week" raw))]
                (if (#{"today" "week"} r) r "week"))
        statuses (into [] (:daily-statuses state))]
    (str "/api/user/gantt/daily?range=" (encode-q range)
         "&statuses=" (encode-q (str/join "," statuses)))))

(defn- page-lead-html [key]
  (str "<p class=\"page-lead\">" (esc (m key)) "</p>"))

(defn- section-title-html [key]
  (str "<h2 class=\"section-title\">" (esc (m key)) "</h2>"))

(defn- gantt-title-select-html [titles selected-id include-none? select-id]
  (str "<select name=\"title_id\""
       (when-not (str/blank? (str select-id))
         (str " id=\"" (esc select-id) "\""))
       ">"
       (when include-none?
         (str "<option value=\"\""
              (when (or (nil? selected-id) (str/blank? (str selected-id))) " selected")
              ">" (esc (m :gantt-title-none)) "</option>"))
       (apply str
              (for [t titles]
                (str "<option value=\"" (esc (:id t)) "\""
                     (when (same-gantt-id? (:id t) selected-id) " selected")
                     ">" (esc (:name t)) "</option>")))
       "</select>"))

(defn- work-related-title-label [state title-id]
  (if-let [t (gantt-title-by-id state title-id)]
    (:name t)
    (m :gantt-title-none)))

(defn- work-targets-fieldset [fields selected-ids]
  (str "<fieldset><legend>" (esc (m :gantt-targets)) "</legend>"
       (apply str
              (for [f fields]
                (let [checked? (some #(same-gantt-id? % (:id f)) selected-ids)]
                  (str "<label><input type=\"checkbox\" name=\"field_ids\" value=\""
                       (esc (:id f)) "\""
                       (when checked? " checked") "> "
                       (esc (:name f)) "</label>"))))
       "</fieldset>"))

(defn works-view [state]
  (let [fields (:fields state)
        defs (gantt-new-defaults)]
    (layout (m :works-title)
            (str (nav-user state)
                 (flash-html state)
                 (if (empty? fields)
                   (str "<p>" (esc (m :works-no-fields)) "</p>")
                   (let [titles (or (:gantt-titles state) [])
                         rows (or (:gantt-rows state) [])
                         sel (gantt-row-by-id state (:gantt-selected state))]
                     (str
                      (page-lead-html :works-lead)
                      "<section class=\"works-list form-section\" id=\"works-list\">"
                      (section-title-html :works-section-list)
                      (if (empty? rows)
                        (str "<p class=\"empty-hint\">" (esc (m :works-empty)) "</p>")
                        (apply str
                               (for [r rows]
                                 (let [selected? (same-gantt-id? (:id r) (:gantt-selected state))]
                                   (str "<form class=\"work-item" (when selected? " selected")
                                        "\" data-act=\"select-gantt-row\" method=\"post\">"
                                        "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                                        "<button type=\"submit\" id=\"work-btn-" (esc (:id r)) "\">"
                                        (esc (:title r))
                                        " / " (esc (work-related-title-label state (:title_id r)))
                                        " (" (esc (:start_at r)) "〜" (esc (:end_at r)) ")"
                                        " [" (esc (execution-status-label (:execution_status r))) "]"
                                        "</button></form>")))))
                      "</section>"
                      "<section class=\"form-section\" id=\"works-add-section\">"
                      (section-title-html :works-section-add)
                      "<p class=\"section-lead\">" (esc (m :works-lead)) "</p>"
                      "<form data-act=\"add-gantt-row\" method=\"post\" id=\"works-add-form\">"
                      "<label>" (esc (m :gantt-title-of-work))
                      (gantt-title-select-html titles nil true "works-add-title-id") "</label>"
                      "<label>" (esc (m :gantt-title-label))
                      "<input id=\"works-new-title\" name=\"title\" value=\"" (esc (:title defs))
                      "\" placeholder=\"" (esc (m :gantt-work-new-placeholder)) "\">"
                      (field-hint (m :gantt-title-hint)) "</label>"
                      "<label>" (esc (m :gantt-start))
                      "<input id=\"works-new-start\" name=\"start_at\" value=\"" (esc (:start defs))
                      "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<label>" (esc (m :gantt-end))
                      "<input id=\"works-new-end\" name=\"end_at\" value=\"" (esc (:end defs))
                      "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<p class=\"field-hint\">" (esc (m :execution-status)) ": "
                      (esc (m :exec-not-started)) "</p>"
                      "<button type=\"submit\" id=\"works-add-btn\">"
                      (esc (m :gantt-work-add)) "</button></form>"
                      "</section>"
                      (when sel
                        (str
                         "<section class=\"form-section\" id=\"works-edit-section\">"
                         (section-title-html :works-section-edit)
                         "<form data-act=\"save-gantt-row\" method=\"post\" id=\"works-save-form\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                         "<label>" (esc (m :gantt-title-of-work))
                         (gantt-title-select-html titles (:title_id sel) true "works-save-title-id") "</label>"
                         "<label>" (esc (m :gantt-title-label))
                         "<input name=\"title\" value=\"" (esc (:title sel)) "\" required>"
                         (field-hint (m :gantt-title-hint)) "</label>"
                         "<label>" (esc (m :gantt-start))
                         "<input name=\"start_at\" value=\"" (esc (:start_at sel)) "\" required></label>"
                         "<label>" (esc (m :gantt-end))
                         "<input name=\"end_at\" value=\"" (esc (:end_at sel)) "\" required></label>"
                         "<label>" (esc (m :execution-status))
                         (execution-status-select-html (:execution_status sel) "works-execution-status")
                         "</label>"
                         "<label>" (esc (m :work-name))
                         "<input name=\"work_name\" list=\"works-work-name-list\" value=\""
                         (esc (or (:work_name sel) "")) "\">"
                         "<datalist id=\"works-work-name-list\">"
                         (apply str (for [nm (:work-names state)]
                                      (str "<option value=\"" (esc nm) "\">")))
                         "</datalist>"
                         (field-hint (m :work-name-hint)) "</label>"
                         (work-targets-fieldset fields (:field_ids sel))
                         "<button type=\"submit\" id=\"works-save-btn\">"
                         (esc (m :btn-save)) "</button></form>"
                         "<form data-act=\"delete-gantt-row\" method=\"post\" id=\"works-delete-form\""
                         " data-confirm=\"" (esc (m :gantt-delete-confirm)) "\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                         "<button type=\"submit\" id=\"works-delete-btn\">"
                         (esc (m :gantt-delete)) "</button></form>"
                         "</section>")))))))))

(defn daily-view [state]
  (let [fields (:fields state)
        range (let [raw (:daily-range state)
                    r (str (if (nil? raw) "week" raw))]
                (if (#{"today" "week"} r) r "week"))
        statuses (into [] (:daily-statuses state))
        rows (into [] (:daily-rows state))
        total (or (:daily-total state) 0)]
    (layout (m :daily-title)
            (str (nav-user state)
                 (flash-html state)
                 (if (empty? fields)
                   (str "<p>" (esc (m :daily-no-fields)) "</p>")
                   (str
                    (page-lead-html :daily-lead)
                    "<section class=\"form-section\" id=\"daily-filter-section\">"
                    (section-title-html :daily-section-filter)
                    "<div class=\"toolbar\" id=\"daily-range-form\">"
                    (select-switch (m :daily-range-label) "daily-range" range
                                   [["today" (m :daily-today)]
                                    ["week" (m :daily-week)]])
                    "</div>"
                    "<form data-act=\"set-daily-statuses\" method=\"post\" id=\"daily-status-filter\">"
                    "<fieldset><legend>" (esc (m :execution-status)) "</legend>"
                    (apply str
                           (for [[v lab] [["not_started" (m :exec-not-started)]
                                          ["in_progress" (m :exec-in-progress)]
                                          ["done" (m :exec-done)]]]
                             (str "<label><input type=\"checkbox\" name=\"status\" value=\"" v "\""
                                  (when (daily-status-on? state v) " checked")
                                  "> " (esc lab) "</label>")))
                    "<button type=\"submit\" id=\"daily-filter-btn\">"
                    (esc (m :daily-filter-apply)) "</button>"
                    "</fieldset></form>"
                    "</section>"
                    (if (empty? statuses)
                      (str "<p id=\"daily-filter-hint\">" (esc (m :daily-filter-empty)) "</p>")
                      (str "<section class=\"daily-list form-section\" id=\"daily-list\">"
                           (section-title-html :daily-section-list)
                           (if (empty? rows)
                             (if (pos? total)
                               (str "<p class=\"empty-hint\">" (esc (m :daily-empty-filtered)) "</p>"
                                    "<p><a data-nav href=\"/works\">" (esc (m :daily-link-works)) "</a></p>")
                               (str "<p class=\"empty-hint\">" (esc (m :daily-empty)) "</p>"
                                    "<p><a data-nav href=\"/works\">" (esc (m :daily-link-works)) "</a></p>"))
                             (apply str
                                    (for [r rows]
                                      (str "<div class=\"daily-item\" id=\"daily-item-" (esc (:id r)) "\">"
                                           "<span class=\"daily-item-title\">" (esc (:title r)) "</span>"
                                           " <span class=\"daily-item-time\">"
                                           (esc (:start_at r)) "〜" (esc (:end_at r)) "</span>"
                                           "<form data-act=\"set-daily-row-status\" method=\"post\" class=\"daily-status-form\">"
                                           "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                                           "<label>" (esc (m :execution-status))
                                           (execution-status-select-html (:execution_status r)
                                                                         (str "daily-status-" (:id r)))
                                           "</label>"
                                           "<button type=\"submit\">" (esc (m :btn-save)) "</button>"
                                           "</form></div>"))))
                           "</section>"))))))))

(defn gantt-view [state]
  (let [fields (:fields state)]
    (layout (m :gantt-title)
            (str (nav-user state)
                 (flash-html state)
                 (if (empty? fields)
                   (str "<p>" (esc (m :gantt-no-fields)) "</p>")
                   (let [axis (if (nil? (:gantt-axis state)) "day" (:gantt-axis state))
                         orient (normalize-gantt-orient (:gantt-orient state))
                         bounds (gantt-axis-bounds axis)
                         titles (if (nil? (:gantt-titles state)) [] (:gantt-titles state))
                         title-sel (gantt-title-by-id state (:gantt-title-selected state))
                         title-rows (gantt-rows-for-title state (:gantt-title-selected state))
                         all-rows (or (:gantt-rows state) [])
                         orphan-n (count (filter #(or (nil? (:title_id %))
                                                      (str/blank? (str (:title_id %))))
                                                 all-rows))
                         sel (gantt-row-by-id state (:gantt-selected state))
                         progress (:gantt-progress state)
                         defs (gantt-new-defaults)
                         applicable? (cond
                                        (nil? sel) false
                                        (not (gantt-row-applicable? sel)) false
                                        (nil? progress) false
                                        :else (boolean (:applicable progress)))
                         target-ids (when applicable? (:field_ids sel))
                         work-name (when applicable? (str (:work_name sel)))
                         time-v? (= "time-v" orient)]
                     (str
                      (page-lead-html :gantt-lead)
                      (when (pos? orphan-n)
                        (str "<p class=\"empty-hint\">" (esc (m :gantt-orphan-hint))
                             " <a data-nav href=\"/works\">" (esc (m :daily-link-works)) "</a></p>"))
                      "<section class=\"gantt-titles form-section\" id=\"gantt-titles\">"
                      (section-title-html :gantt-section-titles)
                      "<div class=\"gantt-title-list\">"
                      (if (empty? titles)
                        (str "<p class=\"empty-hint\">" (esc (m :gantt-title-select)) "</p>")
                        (apply str
                               (for [t titles]
                                 (let [selected? (same-gantt-id? (:id t) (:gantt-title-selected state))]
                                   (str "<form class=\"gantt-title-item" (when selected? " selected")
                                        "\" data-act=\"select-gantt-title\" method=\"post\">"
                                        "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id t)) "\">"
                                        "<button type=\"submit\" id=\"gantt-title-btn-" (esc (:id t)) "\">"
                                        (esc (:name t)) "</button></form>")))))
                      "</div>"
                      "<form data-act=\"add-gantt-title\" method=\"post\" id=\"gantt-title-add-form\">"
                      "<label>" (esc (m :gantt-titles-label))
                      "<input id=\"gantt-new-title-name\" name=\"name\" placeholder=\""
                      (esc (m :gantt-title-new-placeholder)) "\" required></label>"
                      "<button type=\"submit\" id=\"gantt-title-add-btn\">"
                      (esc (m :gantt-title-add)) "</button></form>"
                      (when title-sel
                        (str
                         "<form data-act=\"save-gantt-title\" method=\"post\" id=\"gantt-title-save-form\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id title-sel)) "\">"
                         "<label>" (esc (m :gantt-titles-label))
                         "<input name=\"name\" value=\"" (esc (:name title-sel)) "\" required></label>"
                         "<button type=\"submit\" id=\"gantt-title-save-btn\">"
                         (esc (m :btn-save)) "</button></form>"
                         "<form data-act=\"delete-gantt-title\" method=\"post\" id=\"gantt-title-delete-form\""
                         " data-confirm=\"" (esc (m :gantt-title-delete-confirm)) "\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id title-sel)) "\">"
                         "<button type=\"submit\" id=\"gantt-title-delete-btn\">"
                         (esc (m :gantt-delete)) "</button></form>"))
                      "</section>"
                      (when title-sel
                        (str
                         "<section class=\"gantt-works form-section\" id=\"gantt-works\">"
                         (section-title-html :gantt-section-chart)
                         "<p class=\"section-lead\">" (esc (:name title-sel)) "</p>"
                         "<div class=\"toolbar\">"
                         (select-switch (m :gantt-axis-label) "gantt-axis" axis
                                        [["day" (m :gantt-axis-day)]
                                         ["week" (m :gantt-axis-week)]
                                         ["weeks8" (m :gantt-axis-weeks8)]
                                         ["month" (m :gantt-axis-month)]
                                         ["months3" (m :gantt-axis-months3)]
                                         ["months6" (m :gantt-axis-months6)]])
                         (select-switch (m :gantt-orient-label) "gantt-orient" orient
                                        [["time-h" (m :gantt-orient-time-h)]
                                         ["time-v" (m :gantt-orient-time-v)]])
                         "</div>"
                         "<div id=\"gantt-axis\" class=\"gantt-axis"
                         (when time-v? " gantt-orient-time-v")
                         "\" data-start=\"" (esc (:start bounds))
                         "\" data-end=\"" (esc (:end bounds))
                         "\" data-range=\"" (esc (:range bounds))
                         "\" data-orient=\"" (esc orient) "\">"
                         "<div id=\"gantt-ticks\" class=\"gantt-ticks\"></div>"
                         "<div class=\"gantt-plot\">"
                         "<div id=\"gantt-grid\" class=\"gantt-grid\" aria-hidden=\"true\"></div>"
                         "<div class=\"gantt-rows\">"
                         (if (empty? title-rows)
                           (str "<p class=\"empty-hint\">" (esc (m :works-empty)) "</p>")
                           (apply str
                                  (for [r title-rows]
                                    (let [selected? (same-gantt-id? (:id r) (:gantt-selected state))]
                                      (str "<form class=\"gantt-row" (when selected? " selected")
                                           "\" data-act=\"select-gantt-row\" method=\"post\""
                                           " data-start=\"" (esc (:start_at r)) "\" data-end=\"" (esc (:end_at r)) "\""
                                           " data-id=\"" (esc (:id r)) "\">"
                                           "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                                           "<button type=\"submit\">" (esc (:title r))
                                           " (" (esc (:start_at r)) "〜" (esc (:end_at r)) ")"
                                           " [" (esc (execution-status-label (:execution_status r))) "]"
                                           "</button>"
                                           "<div class=\"gantt-bar\"></div></form>")))))
                         "</div>"
                         "</div>"
                         "</div>"
                         "<section class=\"form-section\" id=\"gantt-add-section\">"
                         (section-title-html :gantt-section-add)
                         "<form data-act=\"add-gantt-row\" method=\"post\" id=\"gantt-add-form\">"
                         "<input type=\"hidden\" name=\"title_id\" value=\"" (esc (:id title-sel)) "\">"
                         "<label>" (esc (m :gantt-title-label))
                         "<input id=\"gantt-new-title\" name=\"title\" value=\"" (esc (:title defs))
                         "\" placeholder=\"" (esc (m :gantt-work-new-placeholder)) "\">"
                         (field-hint (m :gantt-title-hint)) "</label>"
                         "<label>" (esc (m :gantt-start))
                         "<input id=\"gantt-new-start\" name=\"start_at\" value=\"" (esc (:start defs))
                         "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                         "<label>" (esc (m :gantt-end))
                         "<input id=\"gantt-new-end\" name=\"end_at\" value=\"" (esc (:end defs))
                         "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                         "<button type=\"submit\" id=\"gantt-add-btn\">"
                         (esc (m :gantt-work-add)) "</button></form>"
                         "</section>"
                         (when (and sel (same-gantt-id? (:title_id sel) (:id title-sel)))
                           (str
                            "<section class=\"form-section\" id=\"gantt-edit-section\">"
                            (section-title-html :gantt-section-edit)
                            "<form data-act=\"save-gantt-row\" method=\"post\" id=\"gantt-save-form\">"
                            "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                            "<label>" (esc (m :gantt-title-of-work))
                            "<select name=\"title_id\" id=\"gantt-row-title-id\" required>"
                            (apply str
                                   (for [t titles]
                                     (str "<option value=\"" (esc (:id t)) "\""
                                          (when (same-gantt-id? (:id t) (:title_id sel)) " selected")
                                          ">" (esc (:name t)) "</option>")))
                            "</select></label>"
                            "<label>" (esc (m :gantt-title-label))
                            "<input name=\"title\" value=\"" (esc (:title sel)) "\" required>"
                            (field-hint (m :gantt-title-hint)) "</label>"
                            "<label>" (esc (m :gantt-start))
                            "<input name=\"start_at\" value=\"" (esc (:start_at sel)) "\" required></label>"
                            "<label>" (esc (m :gantt-end))
                            "<input name=\"end_at\" value=\"" (esc (:end_at sel)) "\" required></label>"
                            "<label>" (esc (m :execution-status))
                            (execution-status-select-html (:execution_status sel) "gantt-execution-status")
                            "</label>"
                            "<label>" (esc (m :work-name))
                            "<input name=\"work_name\" list=\"gantt-work-name-list\" value=\""
                            (esc (or (:work_name sel) "")) "\">"
                            "<datalist id=\"gantt-work-name-list\">"
                            (apply str (for [nm (:work-names state)]
                                         (str "<option value=\"" (esc nm) "\">")))
                            "</datalist>"
                            (field-hint (m :work-name-hint)) "</label>"
                            "<fieldset><legend>" (esc (m :gantt-targets)) "</legend>"
                            (apply str
                                   (for [f fields]
                                     (let [checked? (some #(same-gantt-id? % (:id f)) (:field_ids sel))]
                                       (str "<label><input type=\"checkbox\" name=\"field_ids\" value=\""
                                            (esc (:id f)) "\""
                                            (when checked? " checked") "> "
                                            (esc (:name f)) "</label>"))))
                            "</fieldset>"
                            "<button type=\"submit\" id=\"gantt-save-btn\">"
                            (esc (m :btn-save)) "</button></form>"
                            "<form data-act=\"delete-gantt-row\" method=\"post\" id=\"gantt-delete-form\""
                            " data-confirm=\"" (esc (m :gantt-delete-confirm)) "\">"
                            "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                            "<button type=\"submit\" id=\"gantt-delete-btn\">"
                            (esc (m :gantt-delete)) "</button></form>"
                            "<form data-act=\"review-gantt-row\" method=\"post\" id=\"gantt-review-form\">"
                            "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                            "<button type=\"submit\" id=\"gantt-review-btn\">"
                            (esc (m :gantt-review)) "</button></form>"
                            (when-let [days (:gantt-progress-days state)]
                              (str "<div id=\"gantt-review-list\" class=\"gantt-review\">"
                                   "<h3>" (esc (m :gantt-review)) "</h3>"
                                   (if (empty? days)
                                     (str "<p>" (esc (m :gantt-review-empty)) "</p>")
                                     (str "<table><thead><tr><th>" (esc (m :gantt-day)) "</th><th>"
                                          (esc (m :gantt-percent-unit)) "</th></tr></thead><tbody>"
                                          (apply str
                                                 (for [d days]
                                                   (str "<tr><td>" (esc (:day d)) "</td><td>"
                                                        (esc (if (:applicable d)
                                                               (str (:percent d))
                                                               (m :gantt-progress-na)))
                                                        "</td></tr>")))
                                          "</tbody></table>"))
                                   "</div>"))
                            "</section>"))
                         "<section class=\"form-section\" id=\"gantt-map-section\">"
                         (section-title-html :gantt-section-map)
                         "<div id=\"gantt-circle\" class=\"gantt-circle\""
                         " data-percent-unit=\"" (esc (m :gantt-percent-unit)) "\""
                         (when applicable?
                           (str " data-percent=\"" (esc (:percent progress)) "\""))
                         "></div>"
                         "<div id=\"ol-map\" class=\"ol-map\" data-gantt-mode=\"1\""
                         (when applicable?
                           (str " data-target-ids=\"" (esc (str/join "," target-ids)) "\""
                                " data-work-name=\"" (esc work-name) "\""))
                          "></div>"
                          "</section>"
                          "</section>")))))))))

(defn render [state]
  (with-ui-lang state
    (fn []
      (if (and (:narrow? state) (contains? #{:fields :map :map-place :works :gantt :daily :orders-new :others} (:page state)))
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
          :works (works-view state)
          :gantt (gantt-view state)
          :daily (daily-view state)
          :orders (orders-view state)
          :orders-new (orders-new-view state)
          :order (order-view state)
          :others (others-view state)
          :relations (relations-view state)
          :gantt-progress (gantt-progress-admin-view state)
          (unknown-view state))))))

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
    {:state (assoc state :flash {:error? true :text (m :unauthorized)})
     :fx [[:nav (login-path (:kind state))]]}

    (and (= :login (:page state)) (:session state))
    {:state state
     :fx [[:nav (home-path (:kind state))]]}

    :else
    {:state state
     :fx [[:html (render state)]]}))

(defn boot [state {:keys [path search narrow? ui-lang]}]
  (let [s (apply-route (assoc state
                              :narrow? (boolean narrow?)
                              :ui-lang (normalize-lang (or ui-lang (:ui-lang state) "ja")))
                       path search)
        token (:token (parse-query search))]
    {:state (assoc s :form (if token {:token token} {}))
     :fx [[:session (:kind s)]]}))

(defn session-loaded [state body]
  (let [s (if (:ok body)
            (cond-> (assoc state :session {:email (:email body)})
              (contains? body :ui_lang)
              (assoc :ui-lang (normalize-lang (:ui_lang body))))
            (assoc state :session nil))]
    (cond
      (and (= :users (:page s)) (:session s))
      {:state s :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]}

      (and (#{:map :map-place :gantt :order :others} (:page s)) (:session s)
           (or (not (:narrow? s)) (#{:order} (:page s))))
      {:state s :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}

      (and (= :fields (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (and (= :works (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (= :daily (:page s))
      (if (and (:session s) (not (:narrow? s)))
        {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}
        (guarded s))

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

      (and (= :works (:page s)) (empty? (:fields s)))
      (guarded s)

      (and (= :daily (:page s)) (empty? (:fields s)))
      (guarded (assoc s :daily-rows []))

      (= :works (:page s))
      {:state s
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]}

      (= :daily (:page s))
      (let [statuses (into [] (:daily-statuses s))]
        (if (empty? statuses)
          (guarded (assoc s :daily-rows [] :daily-total nil))
          {:state s
           :fx [[:api "GET" (daily-query-path s) nil :daily-loaded]
                [:api "GET" "/api/user/gantt" nil :daily-context-loaded]]}))

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

(defn- gantt-title-selected-in? [titles tsel]
  (boolean (loop [xs titles]
             (when-let [t (first xs)]
               (if (same-gantt-id? (:id t) tsel)
                 true
                 (recur (next xs)))))))

(defn- gantt-row-under-title? [rows sel tsel]
  (boolean (loop [xs rows]
             (when-let [r (first xs)]
               (if (and (same-gantt-id? (:id r) sel)
                        (same-gantt-id? (:title_id r) tsel))
                 true
                 (recur (next xs)))))))

(defn gantt-loaded [state body]
  (if-not (:ok body)
    (let [s (assoc state :gantt-rows [] :gantt-titles [] :gantt-title-selected nil
                   :gantt-selected nil :gantt-progress nil
                   :gantt-progress-days nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})
    (let [rows (if (nil? (:rows body)) [] (:rows body))
          titles (if (nil? (:titles body)) [] (:titles body))
          tsel (:gantt-title-selected state)
          tsel' (if (and tsel (gantt-title-selected-in? titles tsel))
                  tsel
                  (when (seq titles)
                    (:id (first titles))))
          sel (:gantt-selected state)
          sel' (when (and sel tsel' (gantt-row-under-title? rows sel tsel'))
                 sel)
          row (when sel' (gantt-row-by-id (assoc state :gantt-rows rows) sel'))
          s (assoc state :gantt-rows rows :gantt-titles titles
                   :gantt-title-selected tsel' :gantt-selected sel' :flash nil
                   :gantt-progress-days (when sel' (:gantt-progress-days state)))]
      (if (gantt-row-applicable? row)
        {:state (assoc s :gantt-progress nil)
         :fx [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]}
        (guarded (assoc s :gantt-progress nil))))))

(defn gantt-save-result [state body]
  (if (:ok body)
    (let [row (:row body)
          id (:id row)
          tid (:title_id row)]
      {:state (assoc state :gantt-selected id :gantt-title-selected tid
                     :gantt-progress-days nil :flash nil)
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})
    (let [code (:code body)
          text (if (= "work_name_required" code)
                 (m :gantt-work-needed)
                 (code-message code))
          s (assoc state :flash {:error? true :text text})]
      {:state s :fx [[:html (render s)]]})))

(defn daily-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :daily-rows (or (:rows body) []) :flash nil))
    (let [s (assoc state :daily-rows []
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn daily-context-loaded [state body]
  (let [total (if (:ok body)
                (count (or (:rows body) []))
                (or (:daily-total state) 0))
        s (assoc state :daily-total total)]
    (guarded s)))

(defn daily-status-save-result [state body]
  (if (:ok body)
    (let [statuses (into [] (:daily-statuses state))]
      (if (empty? statuses)
        (guarded (assoc state :daily-rows [] :flash nil))
        {:state (assoc state :flash nil)
         :fx [[:api "GET" (daily-query-path state) nil :daily-loaded]]}))
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-delete-result [state body]
  (if (:ok body)
    {:state (assoc state :gantt-selected nil :gantt-progress nil :gantt-progress-days nil
                   :flash {:error? false :text (m :gantt-deleted)})
     :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
          [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-title-save-result [state body]
  (if (:ok body)
    (let [tid (get-in body [:title :id])]
      {:state (assoc state :gantt-title-selected tid :flash nil)
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-title-delete-result [state body]
  (if (:ok body)
    {:state (assoc state :gantt-title-selected nil :gantt-selected nil
                   :gantt-progress nil :gantt-progress-days nil
                   :flash {:error? false :text (m :gantt-title-deleted)})
     :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-progress-days-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :gantt-progress-days (or (:days body) []) :flash nil))
    (let [s (assoc state :gantt-progress-days nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-progress-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :gantt-progress body))
    (let [s (assoc state :gantt-progress nil
                   :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-finalize-result [state body]
  (if (:ok body)
    (let [s (assoc state
                   :gantt-finalize-result {:finalized (:finalized body) :day (:day body)}
                   :flash nil)]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :gantt-finalize-result nil
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
                 (= "no_fields" code) (m :order-no-fields)
                 (= "time_invalid" code) (m :order-time-invalid)
                 (= "time_order" code) (m :order-time-order)
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
    (let [s (assoc state :flash {:error? false :text (m :relations-cut-ok)})]
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
    (let [s (assoc state :flash {:error? false :text (m :place-saving)}
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
               busy? (m :emaff-busy)
               (and ok? (= "emaff_partial" (:code body))) (m :emaff-partial)
               ok? (m :emaff-import-ok)
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
    {:state (assoc state :flash {:error? false :text (m :saved-ok)} :form {}
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
      {:state (assoc state :flash {:error? false :text (m :paint-ok)} :form form
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
    {:state (assoc state :flash {:error? false :text (m :basemap-uploaded)})
     :fx [[:api "GET" "/api/user/basemaps" nil :basemaps-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-image-save [state body]
  (if (:ok body)
    {:state (assoc state :flash {:error? false :text (m :image-ok)})
     :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn users-loaded [state body]
  (guarded (assoc state :users (or (:users body) []))))

(defn after-login [state body]
  (if (:ok body)
    {:state (assoc state
                   :session {:email (:email body)}
                   :ui-lang (normalize-lang (:ui_lang body))
                   :flash nil)
     :fx [[:nav (home-path (:kind state))]]}
    {:state (assoc state :flash {:error? true :text (code-message (:code body))})
     :fx [[:html (render (assoc state :flash {:error? true :text (code-message (:code body))}))]]}))

(defn after-logout [state]
  {:state (assoc state :session nil :flash nil)
   :fx [[:restore-guest-lang (:kind state)]
        [:nav (login-path (:kind state))]]})

(defn after-reset-request [state]
  (let [s (assoc state :flash {:error? false :text (m :reset-requested)})]
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
                   :flash {:error? false :text (m :invite-ok)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-password [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (m :password-ok)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-revoke [state]
  {:state state :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]})

(defn after-api-error [state]
  (let [s (assoc state :flash {:error? true :text (m :api-error)})]
    {:state s :fx [[:html (render s)]]}))

(defn- submit-gantt-act [state form act]
  (case act
    "set-gantt-axis"
    (let [axis (str/trim (as-text (:axis form)))
          axis' (if (#{"day" "week" "weeks8" "month" "months3" "months6"} axis) axis "day")
          s (assoc state :gantt-axis axis' :flash nil)]
      {:state s :fx [[:html (render s)]]})
    "set-gantt-orient"
    (let [orient (normalize-gantt-orient (:orient form))
          s (assoc state :gantt-orient orient :flash nil)]
      {:state s :fx [[:html (render s)]]})
    "select-gantt-title"
    (let [id (str/trim (as-text (:id form)))
          s (assoc state :gantt-title-selected (when-not (str/blank? id) id)
                   :gantt-selected nil :gantt-progress nil
                   :gantt-progress-days nil :flash nil)]
      (guarded s))
    "add-gantt-title"
    (let [name (str/trim (as-text (:name form)))]
      (if (str/blank? name)
        (flash-html-state state (code-message "title_required"))
        {:state state
         :fx [[:api "POST" "/api/user/gantt/titles" {:name name}
               :gantt-title-save-result]]}))
    "save-gantt-title"
    (let [id (str/trim (as-text (if (nil? (:id form))
                                  (:gantt-title-selected state)
                                  (:id form))))
          name (str/trim (as-text (:name form)))]
      (cond
        (str/blank? id)
        (flash-html-state state (m :gantt-title-select))
        (str/blank? name)
        (flash-html-state state (code-message "title_required"))
        :else
        {:state state
         :fx [[:api "PUT" (str "/api/user/gantt/titles/" id) {:name name}
               :gantt-title-save-result]]}))
    "delete-gantt-title"
    (let [id (str/trim (as-text (if (nil? (:id form))
                                  (:gantt-title-selected state)
                                  (:id form))))]
      (if (str/blank? id)
        (flash-html-state state (m :gantt-title-select))
        {:state state
         :fx [[:api "DELETE" (str "/api/user/gantt/titles/" id) nil
               :gantt-title-delete-result]]}))
    "select-gantt-row"
    (let [id (str/trim (as-text (:id form)))
          row (gantt-row-by-id state id)
          s (assoc state :gantt-selected (when-not (str/blank? id) id)
                   :gantt-title-selected (or (:title_id row) (:gantt-title-selected state))
                   :gantt-progress-days nil :flash nil)]
      (if (gantt-row-applicable? row)
        {:state (assoc s :gantt-progress nil)
         :fx [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]}
        (guarded (assoc s :gantt-progress nil))))
    "add-gantt-row"
    (let [tid (str/trim (as-text (if (nil? (:title_id form))
                                   (:gantt-title-selected state)
                                   (:title_id form))))
          title (str/trim (as-text (:title form)))
          title' (if (str/blank? title) (m :gantt-work-new-placeholder) title)
          start (str/trim (as-text (:start_at form)))
          end (str/trim (as-text (:end_at form)))]
      (cond
        (and (= :gantt (:page state)) (str/blank? tid))
        (flash-html-state state (m :gantt-title-select))
        (or (str/blank? start) (str/blank? end))
        (flash-html-state state (m :time-invalid))
        :else
        {:state state
         :fx [[:api "POST" "/api/user/gantt"
               {:title title'
                :title_id (when-not (str/blank? tid) tid)
                :start_at start
                :end_at end
                :work_name nil
                :field_ids []}
               :gantt-save-result]]}))
    "save-gantt-row"
    (let [id (str/trim (as-text (if (nil? (:id form)) (:gantt-selected state) (:id form))))
          body (gantt-body-from-form form (:gantt-title-selected state))
          fids (:field_ids body)
          wn (str/trim (as-text (:work_name body)))
          body' (assoc body :title_id (let [tid (str/trim (as-text (:title_id body)))]
                                        (when-not (str/blank? tid) tid)))]
      (cond
        (str/blank? id)
        (flash-html-state state (m :gantt-not-found))
        (and (= :gantt (:page state)) (nil? (:title_id body')))
        (flash-html-state state (m :gantt-title-select))
        (and (seq fids) (str/blank? wn))
        (flash-html-state state (m :gantt-work-needed))
        (or (str/blank? (:start_at body')) (str/blank? (:end_at body')))
        (flash-html-state state (m :time-invalid))
        :else
        {:state state
         :fx [[:api "PUT" (str "/api/user/gantt/" id) body' :gantt-save-result]]}))
    "delete-gantt-row"
    (let [id (str/trim (as-text (if (nil? (:id form)) (:gantt-selected state) (:id form))))]
      (if (str/blank? id)
        (flash-html-state state (m :gantt-not-found))
        {:state state
         :fx [[:api "DELETE" (str "/api/user/gantt/" id) nil :gantt-delete-result]]}))
    "review-gantt-row"
    (let [id (str/trim (as-text (if (nil? (:id form)) (:gantt-selected state) (:id form))))]
      (if (str/blank? id)
        (flash-html-state state (m :gantt-not-found))
        {:state (assoc state :gantt-progress-days nil :flash nil)
         :fx [[:api "GET" (str "/api/user/gantt/" id "/progress-days") nil
               :gantt-progress-days-loaded]]}))
    "finalize-gantt-progress"
    (let [day (str/trim (as-text (:day form)))
          email (str/trim (as-text (:email form)))
          body (cond-> {}
                 (not (str/blank? day)) (assoc :day day)
                 (not (str/blank? email)) (assoc :email email))]
      {:state (assoc state :form {:day day :email email} :gantt-finalize-result nil :flash nil)
       :fx [[:api "POST" "/api/admin/gantt/progress/finalize" body :gantt-finalize-result]]})
    "set-daily-range"
    (let [range (str/trim (as-text (:range form)))
          range' (if (#{"today" "week"} range) range "today")
          s (assoc state :daily-range range' :flash nil)
          statuses (into [] (:daily-statuses s))]
      (if (empty? statuses)
        (guarded (assoc s :daily-rows []))
        {:state s
         :fx [[:api "GET" (daily-query-path s) nil :daily-loaded]]}))
    "set-daily-statuses"
    (let [statuses (form-status-list form)
          allowed #{"not_started" "in_progress" "done"}
          statuses' (vec (filter allowed statuses))
          s (assoc state :daily-statuses statuses' :flash nil)]
      (if (empty? statuses')
        (guarded (assoc s :daily-rows []))
        {:state s
         :fx [[:api "GET" (daily-query-path s) nil :daily-loaded]]}))
    "set-daily-row-status"
    (let [id (str/trim (as-text (:id form)))
          status (str/trim (as-text (:execution_status form)))
          row (gantt-row-by-id (assoc state :gantt-rows (:daily-rows state)) id)]
      (cond
        (or (str/blank? id) (nil? row))
        (flash-html-state state (m :gantt-not-found))
        (not (#{"not_started" "in_progress" "done"} status))
        (flash-html-state state (m :execution-status-invalid))
        :else
        {:state (assoc state :flash nil)
         :fx [[:api "PUT" (str "/api/user/gantt/" id)
               (daily-row-put-body row status)
               :daily-status-save-result]]}))
    nil))

(defn handle [state msg]
  (with-ui-lang state
    (fn []
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
      :gantt-delete-result (gantt-delete-result state arg)
      :daily-loaded (daily-loaded state arg)
      :daily-context-loaded (daily-context-loaded state arg)
      :daily-status-save-result (daily-status-save-result state arg)
      :gantt-title-save-result (gantt-title-save-result state arg)
      :gantt-title-delete-result (gantt-title-delete-result state arg)
      :gantt-progress-loaded (gantt-progress-loaded state arg)
      :gantt-progress-days-loaded (gantt-progress-days-loaded state arg)
      :gantt-finalize-result (gantt-finalize-result state arg)
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
                (assoc s :gantt-selected nil :gantt-title-selected nil
                       :gantt-progress nil :gantt-progress-days nil
                       :gantt-axis "day" :gantt-orient "time-h" :form {} :paint-data nil)

                (= :daily (:page s))
                (assoc s :daily-range "week"
                       :daily-statuses ["not_started" "in_progress"]
                       :daily-rows [] :daily-total nil :flash nil)

                (= :gantt-progress (:page s))
                (assoc s :gantt-finalize-result nil :form {})

                (#{:orders :orders-new :order :others} (:page s))
                (assoc s :order nil :order-map nil :others-paint-data nil
                       :form {} :orders-sent [] :orders-received [] :others-fields [])

                :else s)]
        (if (and (:session s) (= (:kind s) (:kind state)))
          (session-loaded s {:ok true :email (get-in s [:session :email])})
          {:state (assoc s :session nil)
           :fx [[:restore-guest-lang (:kind s)]
                [:session (:kind s)]]}))
      :submit
      (let [act (:act arg)
            form (:form arg)
            kind (:kind state)]
        (case act
          "login" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/login" "/api/user/login") form :login-result]]}
          "logout" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/logout" "/api/user/logout") {} :logout-result]]}
          "reset-request" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password/reset/request" "/api/user/password/reset/request") (assoc form :ui_lang (ui-lang state)) :reset-request-result]]}
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
                           :flash {:error? false :text (m :place-saving)})]
              {:state s
               :fx [[:html (render s)]
                    [:api "PUT" "/api/user/place" form :place-save-result]]}))
          "emaff-import"
          (if (:place-busy state)
            {:state state :fx [[:html (render state)]]}
            (let [s (assoc state :place-busy true
                           :flash {:error? false :text (m :place-saving)})]
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
                                              (contains? form :area_ha) (assoc :area_ha (:area_ha form))
                                              (contains? form :area_m2) (assoc :area_m2 (:area_m2 form))
                                              (contains? form :memo) (assoc :memo (:memo form))
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
                             :flash {:error? true :text (m :work-name-needed)})]
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
              (flash-html-state state (m :work-name-needed))
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
              (flash-html-state state (m :work-name-needed))
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
              (flash-html-state state (m :work-name-needed))
              (nil? fid)
              (flash-html-state state (code-message "field_not_found"))
              :else
              {:state (assoc state :form (assoc (:form state) :work_name wn :field_id fid :id fid))
               :fx [[:api "DELETE" (field-paints-query fid wn) nil :paint-save-result]]}))
          "discard-drafts"
          (let [s (assoc state
                         :form (dissoc (:form state) :paint-geojson)
                         :flash {:error? false :text (m :paint-discard)})]
            {:state s :fx [[:html (render s)]]})
          "import-fields" {:state state :fx [[:upload "POST" "/api/user/fields/import" form :field-save-result]]}
          "save-image-extent" {:state state :fx [[:api "PUT" "/api/user/place/image" form :image-save-result]]}
          "upload-basemap" {:state state :fx [[:upload "PUT" (str "/api/user/basemaps/" (:kind form)) form :basemap-upload-result]]}
          ("set-gantt-axis" "set-gantt-orient" "select-gantt-title" "add-gantt-title"
           "save-gantt-title" "delete-gantt-title" "select-gantt-row" "add-gantt-row"
           "save-gantt-row" "delete-gantt-row" "review-gantt-row" "finalize-gantt-progress"
           "set-daily-range" "set-daily-statuses" "set-daily-row-status")
          (submit-gantt-act state form act)
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
              (flash-html-state state (m :order-not-found))
              {:state state
               :fx [[:api "PUT" (str "/api/user/orders/" id) body :order-save-result]]}))
          "close-order"
          (let [id (str/trim (as-text (or (:id form) (:order-id state) (get-in state [:order :id]))))]
            (if (str/blank? id)
              (flash-html-state state (m :order-not-found))
              {:state state
               :fx [[:api "POST" (str "/api/user/orders/" id "/close") {} :order-save-result]]}))
          "post-journal"
          (let [id (str/trim (as-text (or (:id form) (:order-id state) (get-in state [:order :id]))))]
            (if (str/blank? id)
              (flash-html-state state (m :order-not-found))
              {:state state
               :fx [[:api "POST" (str "/api/user/orders/" id "/journal")
                     {:body (:body form)}
                     :journal-save-result]]}))
          "select-others-work-name"
          (let [wn (str/trim (as-text (:work_name form)))]
            (if (str/blank? wn)
              (flash-html-state (assoc state :form (assoc (:form state) :work_name "")
                                       :others-paint-data nil)
                                (m :work-name-needed))
              {:state (assoc state :form (assoc (:form state) :work_name wn) :flash nil)
               :fx [[:api "GET" (str "/api/user/others/paints?work_name=" (encode-q wn))
                     nil :others-paints-loaded]]}))
          "cut-relation"
          {:state state
           :fx [[:api "POST" "/api/admin/relations/cut" form :relation-cut-result]]}
          {:state state :fx [[:html (render state)]]}))
      :set-lang
      (let [lang (normalize-lang (if (map? arg) (:lang arg) arg))
            s (assoc state :ui-lang lang :flash nil)]
        (if (:session s)
          {:state s
           :fx [[:api "PUT"
                 (if (= "admin" (:kind s)) "/api/admin/language" "/api/user/language")
                 {:ui_lang lang}
                 :language-saved]]}
          {:state s
           :fx [[:guest-lang (:kind s) lang]
                [:html (render s)]]}))
      :language-saved
      (if (:ok arg)
        {:state state :fx [[:html (render state)]]}
        (let [s (assoc state :flash {:error? true
                                     :text (code-message (or (:code arg) "lang_invalid"))})]
          {:state s :fx [[:html (render s)]]}))
      {:state state :fx [[:html (render state)]]})))))
