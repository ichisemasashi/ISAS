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
   :gantt-lead "手順: ①題名を選ぶ → ②棒または一覧から作業を選ぶ → ③基本情報・作業時間・チェックを直す"
   :gantt-orphan-hint "題名のない作業はガントに出ません。作業画面で題名を付けるか、そのまま管理できます"
   :gantt-section-titles "1. 題名を選ぶ"
   :gantt-section-chart "2. 作業を選ぶ（棒またはボタン）"
   :gantt-section-add "この題名に作業を登録"
   :gantt-section-edit "3. 基本情報を直す"
   :gantt-section-map "進捗と地図"
   :gantt-pick-hint "棒または下の作業名を押すと、編集欄と作業時間・チェック項目が出ます"
   :gantt-open-prefix "編集する: "
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
   :gantt-title-saved "題名を保存しました"
   :gantt-title-delete-confirm "この題名と中の作業を一覧から消します。よろしいですか？"
   :gantt-work-add "作業を足す"
   :gantt-work-new-placeholder "新しい作業"
   :gantt-works-label "作業"
   :gantt-title-of-work "関連する題名"
   :gantt-title-none "（なし）"
   :works-title "作業"
   :works-lead "手順: ①一覧から作業を選ぶ → ②基本情報を直す → ③作業時間・チェック項目を足す。新しい作業は一番下から登録します"
   :works-section-list "1. 作業を選ぶ"
   :works-section-add "新しい作業を登録"
   :works-section-edit "2. 基本情報を直す"
   :works-section-children "3. 作業時間とチェック項目"
   :works-section-delete "4. 一覧から消す（ソフト削除）"
   :works-children-lead "この作業に紐づく明細です。上の一覧で作業を選んだあとに足せます"
   :works-pick-hint "上の一覧で作業名のボタンを押すと、ここに編集欄と「作業時間・チェック項目」が出ます"
   :works-editing-prefix "編集中: "
   :works-open-prefix "編集する: "
   :works-open-selected "（いま編集中）"
   :works-basics-lead "題名・時刻・実行状態・作業名・対象圃場を直し「基本情報を保存」を押します"
   :works-basics-save "基本情報を保存"
   :works-delete-lead "パソコンの作業／ガント画面で、選んだ作業を一覧から消します（ソフト削除）。作業時間・チェックも通常の一覧から消えます。メモに付けていた場合は、スレッドの「紐づいた作業」に「削除済み」と出ます"
   :nav-works "作業"
   :nav-gantt "ガント"
   :nav-daily "日次"
   :nav-orders "指示"
   :phone-works "作業の編集はパソコンで開いてください"
   :works-no-fields "圃場が1枚以上あるときだけ、作業を管理できます"
   :works-empty "まだ作業がありません。下の「新しい作業を登録」から作れます"
   :daily-title "日次一覧"
   :daily-lead "今日・今週・先週・前後7日・すべての予定を、未着手／着手中／完了で回す一覧です。終わっていない遅れた作業も出ます"
   :phone-daily "日次一覧はパソコンで開いてください"
   :phone-daily-admin-pc "日次一覧はスマホで開いてください"
   :daily-phone-readonly "スマホでは状態を変えられません"
   :nav-bottom-memos "メモ"
   :nav-bottom-daily "日次"
   :nav-bottom-orders "指示"
   :orders-admin-phone "指示は利用者ログインで開けます"
   :home-memos-more "メモの投稿・検索"
   :daily-no-fields "圃場が1枚以上あるときだけ、日次一覧を使えます"
   :daily-today "今日"
   :daily-week "今週"
   :daily-last-week "先週"
   :daily-around7 "前後7日"
   :daily-all "すべて"
   :daily-overdue "遅れ"
   :daily-range-label "期間"
   :daily-filter-apply "絞り込む"
   :daily-filter-empty "状態フィルタを1つ以上オンにしてください"
   :daily-empty "該当する作業はありません"
   :daily-empty-filtered "選んだ期間・状態に重なる作業がありません。期間を変えるか、作業画面で開始・終了を確認してください"
   :daily-link-works "作業画面を開く"
   :daily-section-list "一覧"
   :daily-section-filter "期間と状態"
   :home-link-daily "今日・今週・前後7日などの予定を実行状態で回す"
   :home-link-works "予定の追加・編集"
   :home-link-gantt "題名ごとの時間軸・進捗・地図"
   :fields-lead "圃場の名前・面積・メモを直します。新しい圃場は地図画面で作ります"
   :invite-lead "相手のメールアドレスを入れると、初期パスワード付きで招待できます"
   :password-lead "今のパスワードを確認してから、新しいパスワードに換えます"
   :work-name-hint "地図の塗りや進捗％と結びつける名前です。無くてもかまいません。塗り・作業・ガント・指示で使った名前は候補から選べます"
   :execution-status "実行状態"
   :exec-not-started "未着手"
   :exec-in-progress "着手中"
   :exec-done "完了"
   :execution-status-invalid "実行状態が正しくありません"
   :range-invalid "期間の指定が正しくありません"
   :statuses-invalid "状態フィルタが正しくありません"
   :work-times "作業時間"
   :work-times-lead "実際に働いた時間帯です。親の予定の外や重なりも登録できます"
   :work-time-add "この時間を追加"
   :work-time-add-heading "新しい作業時間を追加"
   :work-time-list-heading "登録済みの作業時間"
   :work-time-save "この時間を保存"
   :checklist-items "チェック項目"
   :checklist-lead "やることリストです。状態を変えるとすぐ保存されます"
   :checklist-label "項目名"
   :checklist-add "この項目を追加"
   :checklist-add-heading "新しいチェック項目を追加"
   :checklist-list-heading "登録済みのチェック項目"
   :checklist-save "項目名を保存"
   :checklist-done "やった"
   :checklist-pending "まだ"
   :checklist-status "状態"
   :work-time-none "まだ作業時間がありません。上の欄から追加してください"
   :work-time-count-prefix "作業時間 "
   :work-time-count-suffix " 件"
   :checklist-summary-prefix "チェック "
   :checklist-none "まだチェック項目がありません。上の欄から追加してください"
   :daily-work-time-none "作業時間なし"
   :daily-checklist-none "チェックなし"
   :label-required "項目名を入れてください"
   :label-too-long "項目名は200文字以内にしてください"
   :work-time-not-found "その作業時間はありません"
   :checklist-item-not-found "そのチェック項目はありません"
   :checklist-status-invalid "チェックの状態が正しくありません"
   :work-time-delete-confirm "この作業時間を消します。よろしいですか？"
   :checklist-delete-confirm "このチェック項目を消します。よろしいですか？"
   :work-time-deleted "作業時間を消しました"
   :checklist-deleted "チェック項目を消しました"
   :work-time-saved "作業時間を保存しました"
   :checklist-item-saved "チェック項目を保存しました"
   :gantt-row-saved "作業を保存しました"
   :gantt-row-added "作業を登録しました"
   :daily-status-saved "実行状態を保存しました"
   :checklist-left-prefix "完了にしましたが、まだのチェックが "
   :checklist-left-suffix " 件あります。作業を開いて確認してください"
   :daily-checklist-left "チェック残り"
   :daily-work-time-today "今日の作業時間あり"
   :works-filter-empty "選んだ期間・状態に重なる作業がありません。期間または状態を変えてください"
   :sentence-sep "。"
   :daily-link-edit-work "作業を開く"
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
   :gantt-row-delete "一覧から消す"
   :gantt-delete-confirm "この作業を一覧から消します（ソフト削除）。よろしいですか？"
   :gantt-deleted "作業を一覧から消しました"
   :gantt-review "振り返り"
   :gantt-review-empty "確定した日次％はまだありません"
   :gantt-progress-na "—"
   :gantt-day "日付"
   :title-required "題名を入れてください"
   :title-too-long "題名は200文字以内にしてください"
   :time-invalid "開始と終了は分までの日時にしてください"
   :gantt-not-found "その予定はありません"
   :gantt-conflict "ほかの画面でこの作業が変更されています。最新の内容を読み込み直したので、確認してからもう一度保存してください"
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
   :orders-lead "日誌は「受けた指示」を開き、進行中かつ未記入のときだけ書けます。出した指示では日誌は書けません"
   :journal-lead "受け手として「やった」内容を1通だけ残せます。書いたら直せません"
   :journal-body-label "日誌の本文"
   :journal-submit "日誌を投稿する"
   :journal-saved "日誌を書きました"
   :journal-done-hint "この指示へのあなたの日誌は投稿済みです（訂正できません）"
   :journal-closed-hint "閉じた指示には日誌を書けません"
   :journal-issuer-hint "出した人は日誌を書けません。受け手の日誌は下に表示されます"
   :journal-need-open "日誌を書くには、受けた指示の詳細を開いてください"
   :order-journal-pending "日誌未記入"
   :order-journal-done "日誌済"
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
   :lang-invalid "言語の指定が正しくありません"
   :nav-memos "メモ"
   :memos-title "メモ"
   :memos-timeline "タイムライン"
   :memos-drafts "下書き"
   :memos-bookmarks "ブックマーク"
   :memos-compose "投稿"
   :memos-body-label "本文"
   :memos-tags-label "タグ（改行またはカンマ区切り）"
   :memos-links-label "リンク（1行に1つ）"
   :memos-save-draft "下書き保存"
   :memos-publish "公開する"
   :memos-reply "返信"
   :memos-search "検索"
   :memos-advanced-search "高度な検索"
   :memos-exclude "除外語"
   :memos-from "開始日"
   :memos-to "終了日"
   :memos-author "作成者メール"
   :memos-body-required "本文を入力してください"
   :memos-body-limit "本文は2000文字までです"
   :memos-edit-until "編集期限: "
   :memos-edit-window-closed "編集できる期限を過ぎています"
   :memos-star-on "★"
   :memos-star-off "☆"
   :memos-bookmark-add "ブックマーク"
   :memos-bookmark-remove "ブックマークを外す"
   :memos-select-thread "スレッドを開く"
   :memos-thread "スレッド"
   :memos-close-thread "スレッドを閉じる"
   :memos-edit "編集する"
   :memos-more "続きを読み込む"
   :memos-empty "メモはまだありません"
   :memos-drafts-empty "下書きはありません"
   :memos-bookmarks-empty "ブックマークはありません"
   :memos-attach "ファイルを選ぶ"
   :memos-detach "添付を外す"
   :memos-attach-after-save "添付は、下書き保存または公開のあとに追加できます。"
   :memos-uploading "アップロード中…"
   :memos-upload-done "アップロード完了"
   :memos-upload-failed "アップロードに失敗しました"
   :memos-draft-label "下書き"
   :memos-search-results "検索結果"
   :memos-search-query-prefix "検索文字列は「"
   :memos-search-query-suffix "」"
   :memos-search-run "この条件で検索"
   :memos-advanced-open "高度な条件を開く"
   :memos-advanced-close "高度な条件を閉じる"
   :memos-search-clear "検索をやめる"
   :memos-search-empty "該当するメモはありません"
   :phone-memos "メモはパソコンで開いてください"
   :home-link-memos "共有メモのタイムライン"
   :memos-posted "投稿しました"
   :memos-draft-saved "下書きを保存しました"
   :memos-published "公開しました"
   :memos-deleted "削除しました"
   :memos-bookmarked "ブックマークしました"
   :memos-unbookmarked "ブックマークを外しました"
   :memos-gantt-label "紐づいた作業"
   :memos-gantt-summary-hint "ここに出る「紐づいた作業」が、設計上の要約表示です（別の「要約」欄はありません）"
   :memos-gantt-link "作業を紐づける"
   :memos-gantt-retarget "別の作業に付け替える"
   :memos-gantt-unlink "紐づけを外す"
   :memos-gantt-deleted "削除済み"
   :memos-gantt-empty "付けられる作業がありません。作業／ガント画面で作業を登録してください"
   :memos-gantt-select "作業を選ぶ"
   :memos-gantt-linked "作業を紐づけました"
   :memos-gantt-unlinked "紐づけを外しました"
   :work-name-shared-hint "塗り・作業・ガント・指示で使った名前は、どの画面の作業名入力でも候補から選べます"
   :link-not-allowed "下書きや返信には作業を付けられません"
   :gantt-id-required "紐づける作業を選んでください"
   :memo-not-found "そのメモはありません"
   :body-required "本文を入力してください"
   :tag-limit "タグは20件までにしてください"
   :tag-invalid "タグは1文字以上100文字以内にしてください"
   :link-limit "リンクは20件までにしてください"
   :link-invalid "リンクは http:// か https:// で始めてください"
   :attachment-limit "添付は20件までです"
   :forbidden-memo "この操作は作成者だけができます"
   :parent-not-found "返信先のメモがありません"
   :not-draft "このメモはすでに公開されています"
   :bookmark-not-found "外すブックマークがありません"})

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
   :gantt-lead "Steps: ① choose a title → ② select a work (bar or button) → ③ edit basics, work times, and checklist"
   :gantt-orphan-hint "Works without a title do not appear on the Gantt. Open Works to assign a title or manage them there"
   :gantt-section-titles "1. Choose a title"
   :gantt-section-chart "2. Choose a work (bar or button)"
   :gantt-section-add "Add a work under this title"
   :gantt-section-edit "3. Edit basics"
   :gantt-section-map "Progress and map"
   :gantt-pick-hint "Click a bar or work name to open editing, work times, and checklist"
   :gantt-open-prefix "Edit: "
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
   :gantt-title-saved "Title saved"
   :gantt-title-delete-confirm "Remove this title and its works from the list?"
   :gantt-work-add "Add work"
   :gantt-work-new-placeholder "New work"
   :gantt-works-label "Works"
   :gantt-title-of-work "Related title"
   :gantt-title-none "(none)"
   :works-title "Works"
   :works-lead "Steps: ① pick a work → ② edit basics → ③ add work times and checklist items. Register new works at the bottom"
   :works-section-list "1. Choose a work"
   :works-section-add "Register a new work"
   :works-section-edit "2. Edit basics"
   :works-section-children "3. Work times and checklist"
   :works-section-delete "4. Remove from list (soft delete)"
   :works-children-lead "Details for this work. Available after you pick a work above"
   :works-pick-hint "Press a work name above to open editing and “work times / checklist” here"
   :works-editing-prefix "Editing: "
   :works-open-prefix "Edit: "
   :works-open-selected " (editing now)"
   :works-basics-lead "Change title, times, status, work name, and fields, then press “Save basics”"
   :works-basics-save "Save basics"
   :works-delete-lead "On Works or Gantt (computer), soft-delete the selected work from the list. Its work times and checklist disappear from normal views. If a memo was linked, the thread shows “Linked work: … (Deleted)”"
   :nav-works "Works"
   :nav-gantt "Gantt"
   :nav-daily "Daily"
   :nav-orders "Orders"
   :phone-works "Edit works on a computer"
   :works-no-fields "Work management is available only when you have at least one field"
   :works-empty "No works yet. Use “Register a new work” below"
   :daily-title "Daily list"
   :daily-lead "Run plans for today, this week, last week, ±7 days, or all, with Not started / In progress / Done. Unfinished overdue works are also shown"
   :phone-daily "Open the daily list on a computer"
   :phone-daily-admin-pc "Open the daily list on a phone"
   :daily-phone-readonly "Status cannot be changed on a phone"
   :nav-bottom-memos "Memos"
   :nav-bottom-daily "Daily"
   :nav-bottom-orders "Orders"
   :orders-admin-phone "Open orders with a user login"
   :home-memos-more "Compose and search memos"
   :daily-no-fields "Daily list is available only when you have at least one field"
   :daily-today "Today"
   :daily-week "This week"
   :daily-last-week "Last week"
   :daily-around7 "±7 days"
   :daily-all "All"
   :daily-overdue "Overdue"
   :daily-range-label "Range"
   :daily-filter-apply "Apply filters"
   :daily-filter-empty "Turn on at least one status filter"
   :daily-empty "No matching works"
   :daily-empty-filtered "No works overlap the selected range and statuses. Change the range or check start/end on Works"
   :daily-link-works "Open Works"
   :daily-section-list "List"
   :daily-section-filter "Range and status"
   :home-link-daily "Run plans for today, this week, ±7 days and more by status"
   :home-link-works "Add and edit schedules"
   :home-link-gantt "Time axis, progress, and map by title"
   :fields-lead "Edit field names, areas, and memos. Create new fields on the map"
   :invite-lead "Enter the counterpart’s email to invite them with an initial password"
   :password-lead "Confirm your current password, then set a new one"
   :work-name-hint "Links to map paint and progress %. Optional. Names from paints, works, gantt, and orders appear as choices"
   :execution-status "Execution status"
   :exec-not-started "Not started"
   :exec-in-progress "In progress"
   :exec-done "Done"
   :execution-status-invalid "Invalid execution status"
   :range-invalid "Invalid range"
   :statuses-invalid "Invalid status filter"
   :work-times "Work times"
   :work-times-lead "Actual time worked. May fall outside or overlap the parent schedule"
   :work-time-add "Add this time"
   :work-time-add-heading "Add a work time"
   :work-time-list-heading "Saved work times"
   :work-time-save "Save this time"
   :checklist-items "Checklist"
   :checklist-lead "To-do items. Changing status saves immediately"
   :checklist-label "Item name"
   :checklist-add "Add this item"
   :checklist-add-heading "Add a checklist item"
   :checklist-list-heading "Saved checklist items"
   :checklist-save "Save item name"
   :checklist-done "Done"
   :checklist-pending "Pending"
   :checklist-status "Status"
   :work-time-none "No work times yet. Add one above"
   :work-time-count-prefix "Work times: "
   :work-time-count-suffix ""
   :checklist-summary-prefix "Checklist "
   :checklist-none "No checklist items yet. Add one above"
   :daily-work-time-none "No work times"
   :daily-checklist-none "No checklist"
   :label-required "Enter an item name"
   :label-too-long "Item name must be 200 characters or fewer"
   :work-time-not-found "That work time does not exist"
   :checklist-item-not-found "That checklist item does not exist"
   :checklist-status-invalid "Invalid checklist status"
   :work-time-delete-confirm "Remove this work time?"
   :checklist-delete-confirm "Remove this checklist item?"
   :work-time-deleted "Work time removed"
   :checklist-deleted "Checklist item removed"
   :work-time-saved "Work time saved"
   :checklist-item-saved "Checklist item saved"
   :gantt-row-saved "Work saved"
   :gantt-row-added "Work registered"
   :daily-status-saved "Execution status saved"
   :checklist-left-prefix "Marked done, but "
   :checklist-left-suffix " checklist item(s) are still pending. Open the work to check them"
   :daily-checklist-left "Checklist pending"
   :daily-work-time-today "Work time logged today"
   :works-filter-empty "No works overlap the selected period and statuses. Change the period or statuses"
   :sentence-sep ". "
   :daily-link-edit-work "Open work"
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
   :gantt-row-delete "Remove from list"
   :gantt-delete-confirm "Remove this work from the list (soft delete)?"
   :gantt-deleted "Work removed from the list"
   :gantt-review "Review"
   :gantt-review-empty "No finalized daily percent yet"
   :gantt-progress-na "—"
   :gantt-day "Date"
   :title-required "Enter a title"
   :title-too-long "Title must be 200 characters or fewer"
   :time-invalid "Start and end must be date-times to the minute"
   :gantt-not-found "That schedule does not exist"
   :gantt-conflict "This work was changed on another screen. The latest version has been reloaded; check it and save again"
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
   :orders-lead "Open a received order to write a journal when it is still open and you have not written one. Issuers cannot write journals"
   :journal-lead "As a recipient you may leave one “done” note. You cannot edit it afterward"
   :journal-body-label "Journal body"
   :journal-submit "Post journal"
   :journal-saved "Journal saved"
   :journal-done-hint "You already posted a journal for this order (it cannot be edited)"
   :journal-closed-hint "Closed orders cannot accept journals"
   :journal-issuer-hint "Issuers cannot write journals. Recipient journals appear below"
   :journal-need-open "Open a received order’s detail to write a journal"
   :order-journal-pending "Journal pending"
   :order-journal-done "Journal done"
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
   :lang-invalid "Invalid language"
   :nav-memos "Memos"
   :memos-title "Memos"
   :memos-timeline "Timeline"
   :memos-drafts "Drafts"
   :memos-bookmarks "Bookmarks"
   :memos-compose "Compose"
   :memos-body-label "Body"
   :memos-tags-label "Tags (newline or comma)"
   :memos-links-label "Links (one per line)"
   :memos-save-draft "Save draft"
   :memos-publish "Publish"
   :memos-reply "Reply"
   :memos-search "Search"
   :memos-advanced-search "Advanced search"
   :memos-exclude "Exclude"
   :memos-from "From"
   :memos-to "To"
   :memos-author "Author email"
   :memos-body-required "Enter a body"
   :memos-body-limit "Body must be 2000 characters or fewer"
   :memos-edit-until "Editable until: "
   :memos-edit-window-closed "Edit window has closed"
   :memos-star-on "★"
   :memos-star-off "☆"
   :memos-bookmark-add "Bookmark"
   :memos-bookmark-remove "Remove bookmark"
   :memos-select-thread "Open thread"
   :memos-thread "Thread"
   :memos-close-thread "Close thread"
   :memos-edit "Edit"
   :memos-more "Load more"
   :memos-empty "No memos yet"
   :memos-drafts-empty "No drafts"
   :memos-bookmarks-empty "No bookmarks"
   :memos-attach "Choose file"
   :memos-detach "Remove attachment"
   :memos-attach-after-save "Add attachments after saving a draft or publishing."
   :memos-uploading "Uploading…"
   :memos-upload-done "Upload complete"
   :memos-upload-failed "Upload failed"
   :memos-draft-label "Draft"
   :memos-search-results "Search results"
   :memos-search-query-prefix "Search text: \""
   :memos-search-query-suffix "\""
   :memos-search-run "Search with these conditions"
   :memos-advanced-open "Show advanced conditions"
   :memos-advanced-close "Hide advanced conditions"
   :memos-search-clear "Clear search"
   :memos-search-empty "No matching memos"
   :phone-memos "Open memos on a computer"
   :home-link-memos "Shared memo timeline"
   :memos-posted "Posted"
   :memos-draft-saved "Draft saved"
   :memos-published "Published"
   :memos-deleted "Deleted"
   :memos-bookmarked "Bookmarked"
   :memos-unbookmarked "Bookmark removed"
   :memos-gantt-label "Linked work"
   :memos-gantt-summary-hint "“Linked work” below is the design’s summary display (there is no separate Summary field)"
   :memos-gantt-link "Link work"
   :memos-gantt-retarget "Link a different work"
   :memos-gantt-unlink "Unlink work"
   :memos-gantt-deleted "Deleted"
   :memos-gantt-empty "No work available to link. Register a work on Works or Gantt first"
   :memos-gantt-select "Choose work"
   :memos-gantt-linked "Work linked"
   :memos-gantt-unlinked "Work unlinked"
   :work-name-shared-hint "Names used in paints, works, gantt, or orders appear as choices wherever you enter a work name"
   :link-not-allowed "Drafts and replies cannot be linked to work"
   :gantt-id-required "Choose a work to link"
   :memo-not-found "That memo does not exist"
   :body-required "Enter a body"
   :tag-limit "Use 20 tags or fewer"
   :tag-invalid "Each tag must be 1 to 100 characters"
   :link-limit "Use 20 links or fewer"
   :link-invalid "Links must start with http:// or https://"
   :attachment-limit "Up to 20 attachments"
   :forbidden-memo "Only the author can do this"
   :parent-not-found "The memo you are replying to does not exist"
   :not-draft "This memo is already published"
   :bookmark-not-found "There is no bookmark of yours to remove"})


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

(defn format-display-instant
  "API の UTC ISO または Asia/Tokyo の local-minute を、画面用の日本時間に直す。"
  [s]
  (let [raw (str/trim (str (or s "")))]
    (when-not (str/blank? raw)
      (or
       #?(:clj
          (try
            (let [inst (cond
                         (re-find #"(?i)Z$|[+-]\d{2}:?\d{2}$" raw)
                         (time/parse-instant raw)

                         (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" raw)
                         (when-let [ldt (time/parse-local-minute (subs raw 0 (min 16 (count raw))))]
                           (.toInstant (.atZone ldt time/tokyo)))

                         :else nil)]
              (when inst
                (let [zdt (.atZone inst time/tokyo)
                      date (str (.getYear zdt) "-" (pad2 (.getMonthValue zdt)) "-" (pad2 (.getDayOfMonth zdt)))
                      time (str (pad2 (.getHour zdt)) ":" (pad2 (.getMinute zdt)))]
                  (format-display-datetime date time))))
            (catch Exception _ nil))
          :cljs
          (try
            (let [iso (cond
                        (re-find #"(?i)Z$|[+-]\d{2}:?\d{2}$" raw) raw
                        (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$" raw) (str raw ":00+09:00")
                        (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" raw) raw
                        :else nil)
                  d (when iso (js/Date. iso))]
              (when (and d (not (js/isNaN (.getTime d))))
                (let [parts (.formatToParts
                             (js/Intl.DateTimeFormat. "en-US"
                                                      #js {:timeZone "Asia/Tokyo"
                                                           :year "numeric"
                                                           :month "2-digit"
                                                           :day "2-digit"
                                                           :hour "2-digit"
                                                           :minute "2-digit"
                                                           :hour12 false})
                             d)
                      get (fn [t]
                            (some (fn [p]
                                    (when (= t (.-type p)) (.-value p)))
                                  (array-seq parts)))
                      date (str (get "year") "-" (get "month") "-" (get "day"))
                      time (str (get "hour") ":" (get "minute"))]
                  (format-display-datetime date time))))
            (catch :default _ nil)))
       raw))))

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
    "gantt_conflict" (m :gantt-conflict)
    "title_not_found" (m :gantt-title-select)
    "user_not_found" (m :user-not-found)
    "title_required" (m :title-required)
    "title_too_long" (m :title-too-long)
    "time_invalid" (m :time-invalid)
    "time_order" (m :gantt-time-order)
    "execution_status_invalid" (m :execution-status-invalid)
    "range_invalid" (m :range-invalid)
    "statuses_invalid" (m :statuses-invalid)
    "work_time_not_found" (m :work-time-not-found)
    "checklist_item_not_found" (m :checklist-item-not-found)
    "label_required" (m :label-required)
    "label_too_long" (m :label-too-long)
    "checklist_status_invalid" (m :checklist-status-invalid)
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
    "memo_not_found" (m :memo-not-found)
    "body_required" (m :memos-body-required)
    "tag_limit" (m :tag-limit)
    "tag_invalid" (m :tag-invalid)
    "link_limit" (m :link-limit)
    "link_invalid" (m :link-invalid)
    "attachment_limit" (m :attachment-limit)
    "edit_window_closed" (m :memos-edit-window-closed)
    "forbidden_memo" (m :forbidden-memo)
    "parent_not_found" (m :parent-not-found)
    "not_draft" (m :not-draft)
    "bookmark_not_found" (m :bookmark-not-found)
    "link_not_allowed" (m :link-not-allowed)
    "gantt_id_required" (m :gantt-id-required)
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
          ;; daily / orders / memos は利用者・管理者で共有。kind を固定しない（セッションの種別を保つ）。
          "/daily" {:page :daily}
          "/orders" {:page :orders}
          "/orders/new" {:page :orders-new :kind "user"}
          "/others" {:page :others :kind "user"}
          "/memos" {:page :memos}
          "/memos/drafts" {:page :memos-drafts}
          "/memos/bookmarks" {:page :memos-bookmarks}
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
          {:page :order :order-id id})
        {:page :unknown :kind "user"})))

(defn login-path [kind]
  (if (= kind "admin") "/admin" "/"))

(defn home-path [kind]
  (if (= kind "admin") "/admin/home" "/home"))

(defn needs-auth? [page]
  (contains? #{:home :invite :password :users :fields :map :map-place :works :gantt :daily
               :orders :orders-new :order :others :relations :gantt-progress
               :memos :memos-drafts :memos-bookmarks} page))

(def ^:private works-default-range "all")

(def ^:private works-default-statuses ["not_started" "in_progress" "done"])

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
   :gantt-windows nil
   :works-range works-default-range
   :works-statuses works-default-statuses
   :gantt-titles []
   :gantt-title-selected nil
   :gantt-selected nil
   :gantt-progress nil
   :gantt-progress-days nil
   :gantt-work-times []
   :gantt-checklist-items []
   :gantt-axis "day"
   :gantt-orient "time-h"
   :gantt-finalize-result nil
   :daily-range "around7"
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
   :others-paint-data nil
   :memos []
   :memo-selected nil
   :memo-selected-row nil
   :memo-replies []
   :memo-drafts []
   :memo-bookmarks []
   :memo-search-q ""
   :memo-search-form {}
   :memo-search-advanced? false
   :memo-search-results nil
   :memo-search-active? false
   :memo-compose {}
   :memo-last-saved nil
   :memo-before-id nil
   :memo-gantt-candidates []
   :memo-gantt-pick nil})

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

(declare render)

(defn flash-html
  "ページ先頭用。:near 付き（操作箇所寄り）の flash はここでは出さない。"
  [state]
  (when-let [f (:flash state)]
    (when (str/blank? (str (or (:near f) "")))
      (str "<p class=\"flash " (if (:error? f) "error" "ok") "\">"
           (esc (:text f)) "</p>"))))

(defn flash-at
  "操作箇所の直上用。flash の :near が near-id と一致するときだけ出す。"
  [state near-id]
  (when-let [f (:flash state)]
    (when (= (str (:near f)) (str near-id))
      (str "<p class=\"flash " (if (:error? f) "error" "ok")
           "\" id=\"flash-" (esc near-id) "\">"
           (esc (:text f)) "</p>"))))

(defn- flash-html-state
  ([state text] (flash-html-state state text nil))
  ([state text near]
   (let [s (assoc state :flash (cond-> {:error? true :text text}
                                 (not (str/blank? (str near))) (assoc :near (str near))))]
     {:state s :fx [[:html (render s)]]})))

(defn- flash-ok-state
  [state text near]
  (assoc state :flash (if (str/blank? (str near))
                        {:error? false :text text}
                        {:error? false :text text :near (str near)})))

(defn- children-prefix [state]
  (if (= :gantt (:page state)) "gantt" "works"))

(defn- children-near [state suffix]
  (str (children-prefix state) suffix))

(defn layout [title body]
  (str "<main id=\"app-main\">" "<h1>" (esc title) "</h1>" body "</main>"))

(defn- bottom-nav-pages? [page]
  (not (contains? #{:login :reset-request :reset :unknown} page)))

(defn- show-bottom-nav? [state]
  (and (boolean (:narrow? state))
       (some? (:session state))
       (bottom-nav-pages? (:page state))))

(defn- bottom-nav [state]
  (when (show-bottom-nav? state)
    (let [page (:page state)
          home (home-path (:kind state))
          memo-on? (contains? #{:home :memos :memos-drafts :memos-bookmarks} page)
          daily-on? (= :daily page)
          orders-on? (contains? #{:orders :order} page)]
      (str "<nav class=\"bottom-nav\" id=\"bottom-nav\" aria-label=\"main\">"
           "<a data-nav href=\"" (esc home) "\"" (when memo-on? " class=\"current\"") ">"
           (esc (m :nav-bottom-memos)) "</a>"
           "<a data-nav href=\"/daily\"" (when daily-on? " class=\"current\"") ">"
           (esc (m :nav-bottom-daily)) "</a>"
           "<a data-nav href=\"/orders\"" (when orders-on? " class=\"current\"") ">"
           (esc (m :nav-bottom-orders)) "</a>"
           "</nav>"))))

(defn- with-bottom-nav [state html]
  (if (show-bottom-nav? state)
    (str html (bottom-nav state))
    html))

(defn- home-after-login [state]
  (home-path (:kind state)))

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
  (let [narrow? (boolean (:narrow? state))]
    (str "<nav>"
         (lang-switcher state)
         "<a data-nav href=\"/home\">" (esc (m :nav-home)) "</a>"
         (when-not narrow?
           (str "<a data-nav href=\"/daily\">" (esc (m :nav-daily)) "</a>"
                "<a data-nav href=\"/works\">" (esc (m :nav-works)) "</a>"
                "<a data-nav href=\"/gantt\">" (esc (m :nav-gantt)) "</a>"
                "<a data-nav href=\"/fields\">" (esc (m :nav-fields)) "</a>"
                "<a data-nav href=\"/map\">" (esc (m :nav-map)) "</a>"
                "<a data-nav href=\"/memos\">" (esc (m :nav-memos)) "</a>"))
         "<a data-nav href=\"/orders\">" (esc (m :nav-orders)) "</a>"
         "<a data-nav href=\"/invite\">" (esc (m :nav-invite)) "</a>"
         "<a data-nav href=\"/password\">" (esc (m :nav-password)) "</a>"
         "<form data-act=\"logout\" method=\"post\"><button type=\"submit\">"
         (esc (m :nav-logout)) "</button></form></nav>")))

(defn nav-admin [state]
  (let [narrow? (boolean (:narrow? state))]
    (str "<nav>"
         (lang-switcher state)
         "<a data-nav href=\"/admin/home\">" (esc (m :nav-home)) "</a>"
         "<a data-nav href=\"/admin/invite\">" (esc (m :nav-invite)) "</a>"
         "<a data-nav href=\"/admin/users\">" (esc (m :nav-users)) "</a>"
         "<a data-nav href=\"/admin/relations\">" (esc (m :nav-relations)) "</a>"
         "<a data-nav href=\"/admin/gantt-progress\">" (esc (m :nav-gantt-progress)) "</a>"
         (when-not narrow?
           (str "<a data-nav href=\"/memos\">" (esc (m :nav-memos)) "</a>"))
         "<a data-nav href=\"/admin/password\">" (esc (m :nav-password)) "</a>"
         "<form data-act=\"logout\" method=\"post\"><button type=\"submit\">"
         (esc (m :nav-logout)) "</button></form></nav>")))

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

(defn- home-memo-timeline-html [state]
  (let [rows (into [] (:memos state))]
    (str "<section class=\"form-section memo-timeline\" id=\"memo-timeline\">"
         "<h2 class=\"section-title\">" (esc (m :memos-timeline)) "</h2>"
         (flash-at state "memo-timeline")
         (if (empty? rows)
           (str "<p class=\"empty-hint\">" (esc (m :memos-empty)) "</p>")
           (str "<ul class=\"memo-list\">"
                (apply str
                       (for [r rows]
                         (str "<li class=\"memo-item\" id=\"memo-item-" (esc (:id r)) "\">"
                              "<p class=\"memo-meta\">" (esc (:author_email r)) "</p>"
                              "<div class=\"memo-body\">" (esc (:body r)) "</div>"
                              "</li>")))
                "</ul>"
                (when (:id (last rows))
                  (str "<form data-act=\"memo-more\" method=\"post\" class=\"inline\">"
                       "<input type=\"hidden\" name=\"before_id\" value=\""
                       (esc (:id (last rows))) "\">"
                       "<button type=\"submit\" id=\"memo-more-btn\">"
                       (esc (m :memos-more)) "</button></form>"))))
         "<p class=\"home-memos-more\"><a data-nav href=\"/memos\">"
         (esc (m :home-memos-more)) "</a></p>"
         "</section>")))

(defn home-view [state]
  (let [admin? (= "admin" (:kind state))
        narrow? (boolean (:narrow? state))
        title (if admin? (m :admin-home) (m :user-home))]
    (layout title
            (str (if admin? (nav-admin state) (nav-user state))
                 (flash-html state)
                 "<p>" (esc (get-in state [:session :email])) "</p>"
                 (when narrow?
                   (home-memo-timeline-html state))
                 (when (and (not admin?) (seq (:fields state)) (not narrow?))
                   (str "<p><a data-nav href=\"/daily\">" (esc (m :daily-title)) "</a>"
                        " — " (esc (m :home-link-daily)) "</p>"
                        "<p><a data-nav href=\"/works\">" (esc (m :works-title)) "</a>"
                        " — " (esc (m :home-link-works)) "</p>"
                        "<p><a data-nav href=\"/gantt\">" (esc (m :gantt-title)) "</a>"
                        " — " (esc (m :home-link-gantt)) "</p>"
                        "<p><a data-nav href=\"/orders/new\">" (esc (m :orders-create)) "</a></p>"))
                 (when (and (not admin?) (not narrow?))
                   (str "<p><a data-nav href=\"/orders\">" (esc (m :orders-title)) "</a></p>"
                        "<p><a data-nav href=\"/others\">" (esc (m :others-title)) "</a></p>"))
                 (when-not narrow?
                   (str "<p><a data-nav href=\"/memos\">" (esc (m :memos-title)) "</a>"
                        " — " (esc (m :home-link-memos)) "</p>"))))))

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
               (flash-at state "invite-form-section")
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
               (flash-at state "password-form-section")
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
           (let [journal-badge
                 (when (= "recipient" (:role o))
                   (cond
                     (= "closed" (:status o))
                     nil
                     (:my_journal o)
                     (str "［" (esc (m :order-journal-done)) "］")
                     :else
                     (str "［" (esc (m :order-journal-pending)) "］")))]
             (str "<li><a data-nav href=\"/orders/" (esc (:id o)) "\">"
                  (esc (or (format-display-date (:work_date o)) (:work_date o))) " "
                  (esc (:work_name o)) "（" (esc (order-status-label (:status o))) "）"
                  (or journal-badge "")
                  "</a></li>")))))

(defn orders-view [state]
  (layout (m :orders-title)
          (str (if (= "admin" (:kind state)) (nav-admin state) (nav-user state))
               (flash-html state)
               (if (= "admin" (:kind state))
                 (str "<p id=\"orders-admin-hint\">" (esc (m :orders-admin-phone)) "</p>")
                 (str (when (and (not (:narrow? state)) (seq (:fields state)))
                        (str "<p><a data-nav href=\"/orders/new\">" (esc (m :orders-create)) "</a></p>"))
                      "<p class=\"page-lead\" id=\"orders-lead\">" (esc (m :orders-lead)) "</p>"
                      "<h2>" (esc (m :order-sent)) "</h2>"
                      "<ul>" (order-list-items (:orders-sent state)) "</ul>"
                      "<h2>" (esc (m :order-received)) "</h2>"
                      "<ul id=\"orders-received-list\">" (order-list-items (:orders-received state)) "</ul>"
                      "<p class=\"field-hint\" id=\"orders-journal-howto\">"
                      (esc (m :journal-need-open)) "</p>")))))

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
                   (flash-at state "orders-new-section")
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
                   "<h2 id=\"order-journals-heading\">" (esc (m :order-journals)) "</h2>"
                   "<p class=\"section-lead\" id=\"order-journal-lead\">" (esc (m :journal-lead)) "</p>"
                   "<ul id=\"order-journals-list\">"
                   (apply str
                          (for [j (:journals o)]
                            (str "<li>" (esc (:author_email j)) ": " (esc (:body j)) "</li>")))
                   "</ul>"
                   (cond
                     can-journal?
                     (str "<section class=\"form-section\" id=\"order-journal-form-section\">"
                          (flash-at state "order-journal-form-section")
                          "<form data-act=\"post-journal\" method=\"post\" id=\"order-journal-form\">"
                          "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id o)) "\">"
                          "<label>" (esc (m :journal-body-label))
                          "<textarea name=\"body\" id=\"order-journal-body\" required"
                          " placeholder=\"" (esc (m :journal-body-label)) "\"></textarea></label>"
                          "<button type=\"submit\" id=\"order-journal-submit\" class=\"btn-primary\">"
                          (esc (m :journal-submit)) "</button></form></section>")
                     (and recipient? open?)
                     (str "<p class=\"field-hint\" id=\"order-journal-done\">"
                          (esc (m :journal-done-hint)) "</p>")
                     (and recipient? (not open?))
                     (str "<p class=\"field-hint\" id=\"order-journal-closed\">"
                          (esc (m :journal-closed-hint)) "</p>")
                     issuer?
                     (str "<p class=\"field-hint\" id=\"order-journal-issuer\">"
                          (esc (m :journal-issuer-hint)) "</p>")
                     :else nil))))))

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
        hide (fn [ok?] (when-not ok? " hidden"))]
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
              "<form data-act=\"confirm-paint\" method=\"post\" class=\"inline\""
              (hide has-draft?) ">"
              "<input type=\"hidden\" name=\"field_id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<input type=\"hidden\" name=\"geojson\" value=\"" (esc gj) "\">"
              "<button type=\"submit\" id=\"paint-confirm-btn\" data-need=\"draft\">"
              (esc (m :paint-confirm)) "</button></form>"
              "<button type=\"button\" id=\"paint-discard-btn\" data-map=\"discard\" data-need=\"draft\""
              (hide has-draft?)
              " data-hint=\"" (esc (m :map-hint-brush)) "\">"
              (esc (m :paint-discard)) "</button>"
              (when-not has-draft?
                (str "<p class=\"map-actions-note\" id=\"paint-draft-note\">"
                     (esc (m :map-need-draft)) "</p>"))
              "</div>"))
        (map-action-section
         (m :map-section-field)
         (str "<p class=\"map-actions-note\" id=\"paint-field-note\""
              (when has-field? " hidden") ">"
              (esc (m :map-need-field)) "</p>"
              "<form data-act=\"complete-field\" method=\"post\" class=\"inline\""
              (hide has-field?) ">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<button type=\"submit\" id=\"paint-complete-btn\" data-need=\"field\">"
              (esc (m :paint-complete)) "</button></form>"
              "<form data-act=\"delete-field-paints\" method=\"post\" class=\"inline\""
              (hide has-field?) ">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc fid) "\">"
              "<input type=\"hidden\" name=\"work_name\" value=\"" (esc wn) "\">"
              "<button type=\"submit\" id=\"paint-delete-all-btn\" data-need=\"field\">"
              (esc (m :paint-delete-all)) "</button></form>"))
        (map-action-section
         (m :map-section-stroke)
         (str "<p class=\"map-actions-note\" id=\"paint-stroke-note\""
              (when has-paint? " hidden") ">"
              (esc (m :map-need-paint)) "</p>"
              "<form data-act=\"delete-paint\" method=\"post\" class=\"inline\""
              (hide has-paint?) ">"
              "<input type=\"hidden\" name=\"id\" value=\"" (esc pid) "\">"
              "<button type=\"submit\" id=\"paint-delete-btn\" data-need=\"paint\">"
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

(defn- gantt-children-load-fx [gid]
  (let [id (str gid)]
    [[:api "GET" (str "/api/user/gantt/" id "/work-times") nil :work-times-loaded]
     [:api "GET" (str "/api/user/gantt/" id "/checklist-items") nil :checklist-items-loaded]]))

(defn- work-duration-minutes [start end]
  #?(:clj
     (try
       (let [a (time/parse-local-minute start)
             b (time/parse-local-minute end)]
         (.toMinutes (java.time.Duration/between a b)))
       (catch Exception _ nil))
     :cljs
     (let [a (.getTime (js/Date. (str start ":00+09:00")))
           b (.getTime (js/Date. (str end ":00+09:00")))]
       (when (and (js/isFinite a) (js/isFinite b) (> b a))
         (js/Math.round (/ (- b a) 60000.0))))))

(defn- work-duration-label [start end]
  (when-let [mins (work-duration-minutes start end)]
    (when (pos? mins)
      (let [h (quot mins 60)
            m (mod mins 60)]
        (if (en-ui?)
          (cond
            (and (pos? h) (pos? m)) (str h "h " m "m")
            (pos? h) (str h "h")
            :else (str m "m"))
          (cond
            (and (pos? h) (pos? m)) (str h "時間" m "分")
            (pos? h) (str h "時間")
            :else (str m "分")))))))

(defn- checklist-status-select-html [selected select-id]
  (let [cur (let [s (str (or selected "pending"))]
              (if (#{"pending" "done"} s) s "pending"))]
    (str "<select name=\"status\" data-select=\"checklist-status\""
         (when-not (str/blank? (str select-id))
           (str " id=\"" (esc select-id) "\""))
         ">"
         (apply str
                (for [[v lab] [["pending" (m :checklist-pending)]
                               ["done" (m :checklist-done)]]]
                  (str "<option value=\"" v "\""
                       (when (= v cur) " selected")
                       ">" (esc lab) "</option>")))
         "</select>")))

(defn- daily-work-time-summary [n]
  (let [c (or n 0)]
    (if (pos? c)
      (str (m :work-time-count-prefix) c (m :work-time-count-suffix))
      (m :daily-work-time-none))))

(defn- checklist-left [done total]
  (max 0 (- (or total 0) (or done 0))))

(defn- with-checklist-left [text left]
  (if (pos? (or left 0))
    (str text (m :sentence-sep) (m :checklist-left-prefix) left (m :checklist-left-suffix))
    text))

(defn- daily-checklist-summary [done total]
  (let [t (or total 0)
        d (or done 0)]
    (if (pos? t)
      (str (m :checklist-summary-prefix) d "/" t)
      (m :daily-checklist-none))))

(defn- gantt-children-edit-html [state gid prefix]
  (let [id (str gid)
        times (or (:gantt-work-times state) [])
        items (or (:gantt-checklist-items state) [])]
    (str
     "<div class=\"gantt-children\" id=\"" (esc prefix) "-children\">"
     "<h2 class=\"section-title\">" (esc (m :works-section-children)) "</h2>"
     "<p class=\"section-lead\">" (esc (m :works-children-lead)) "</p>"
     (flash-at state (str prefix "-children"))
     "<section class=\"child-panel\" id=\"" (esc prefix) "-work-times\">"
     "<h3>" (esc (m :work-times)) "</h3>"
     "<p class=\"section-lead\">" (esc (m :work-times-lead)) "</p>"
     (flash-at state (str prefix "-work-times"))
     "<div class=\"child-add\" id=\"" (esc prefix) "-work-time-add-box\">"
     "<h4>" (esc (m :work-time-add-heading)) "</h4>"
     (flash-at state (str prefix "-work-time-add-box"))
     "<form data-act=\"add-work-time\" method=\"post\" id=\"" (esc prefix) "-work-time-add\">"
     "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
     "<label>" (esc (m :gantt-start))
     "<input type=\"datetime-local\" name=\"start_at\" placeholder=\"YYYY-MM-DDTHH:MM\" required></label>"
     "<label>" (esc (m :gantt-end))
     "<input type=\"datetime-local\" name=\"end_at\" placeholder=\"YYYY-MM-DDTHH:MM\" required></label>"
     "<button type=\"submit\" class=\"btn-primary\">" (esc (m :work-time-add)) "</button></form>"
     "</div>"
     "<div class=\"child-list\">"
     "<h4>" (esc (m :work-time-list-heading)) "</h4>"
     (if (empty? times)
       (str "<p class=\"empty-hint\">" (esc (m :work-time-none)) "</p>")
       (apply str
              (for [t times]
                (let [dur (work-duration-label (:start_at t) (:end_at t))]
                  (str "<div class=\"work-time-item\" id=\"" (esc prefix) "-wt-" (esc (:id t)) "\">"
                       "<form data-act=\"save-work-time\" method=\"post\">"
                       "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
                       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id t)) "\">"
                       "<label>" (esc (m :gantt-start))
                       "<input type=\"datetime-local\" name=\"start_at\" value=\"" (esc (:start_at t)) "\" required></label>"
                       "<label>" (esc (m :gantt-end))
                       "<input type=\"datetime-local\" name=\"end_at\" value=\"" (esc (:end_at t)) "\" required></label>"
                       (when dur (str "<span class=\"work-time-duration\">" (esc dur) "</span>"))
                       "<button type=\"submit\">" (esc (m :work-time-save)) "</button></form>"
                       "<form data-act=\"delete-work-time\" method=\"post\" class=\"inline\""
                       " data-confirm=\"" (esc (m :work-time-delete-confirm)) "\">"
                       "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
                       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id t)) "\">"
                       "<button type=\"submit\">" (esc (m :gantt-delete)) "</button></form>"
                       "</div>")))))
     "</div>"
     "</section>"
     "<section class=\"child-panel\" id=\"" (esc prefix) "-checklist\">"
     "<h3>" (esc (m :checklist-items)) "</h3>"
     "<p class=\"section-lead\">" (esc (m :checklist-lead)) "</p>"
     (flash-at state (str prefix "-checklist"))
     "<div class=\"child-add\" id=\"" (esc prefix) "-checklist-add-box\">"
     "<h4>" (esc (m :checklist-add-heading)) "</h4>"
     (flash-at state (str prefix "-checklist-add-box"))
     "<form data-act=\"add-checklist-item\" method=\"post\" id=\"" (esc prefix) "-checklist-add\">"
     "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
     "<label>" (esc (m :checklist-label))
     "<input name=\"label\" required></label>"
     "<button type=\"submit\" class=\"btn-primary\">" (esc (m :checklist-add)) "</button></form>"
     "</div>"
     "<div class=\"child-list\">"
     "<h4>" (esc (m :checklist-list-heading)) "</h4>"
     (if (empty? items)
       (str "<p class=\"empty-hint\">" (esc (m :checklist-none)) "</p>")
       (apply str
              (for [c items]
                (str "<div class=\"checklist-item\" id=\"" (esc prefix) "-ci-" (esc (:id c)) "\">"
                     "<form data-act=\"save-checklist-item\" method=\"post\" class=\"checklist-status-form\">"
                     "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
                     "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id c)) "\">"
                     "<input type=\"hidden\" name=\"label\" value=\"" (esc (:label c)) "\">"
                     "<label>" (esc (m :checklist-status))
                     (checklist-status-select-html (:status c) (str prefix "-ci-status-" (:id c)))
                     "</label></form>"
                     "<form data-act=\"save-checklist-item\" method=\"post\">"
                     "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
                     "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id c)) "\">"
                     "<input type=\"hidden\" name=\"status\" value=\"" (esc (:status c)) "\">"
                     "<label>" (esc (m :checklist-label))
                     "<input name=\"label\" value=\"" (esc (:label c)) "\" required></label>"
                     "<button type=\"submit\">" (esc (m :checklist-save)) "</button></form>"
                     "<form data-act=\"delete-checklist-item\" method=\"post\" class=\"inline\""
                     " data-confirm=\"" (esc (m :checklist-delete-confirm)) "\">"
                     "<input type=\"hidden\" name=\"gantt_id\" value=\"" (esc id) "\">"
                     "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id c)) "\">"
                     "<button type=\"submit\">" (esc (m :gantt-delete)) "</button></form>"
                     "</div>"))))
     "</div>"
     "</section>"
     "</div>")))

(defn- daily-status-on? [state status]
  (boolean (some #(= (str %) (str status)) (into [] (:daily-statuses state)))))

(def ^:private daily-ranges #{"today" "week" "last_week" "around7" "all"})

(def ^:private daily-default-range "around7")

(defn- current-daily-range [state]
  (let [r (str (:daily-range state))]
    (if (contains? daily-ranges r) r daily-default-range)))

(defn- daily-query-path [state]
  (let [range (current-daily-range state)
        statuses (into [] (:daily-statuses state))
        base (if (= "admin" (:kind state))
               "/api/admin/gantt/daily"
               "/api/user/gantt/daily")]
    (str base "?range=" (encode-q range)
         "&statuses=" (encode-q (str/join "," statuses)))))

(defn- page-lead-html [key]
  (str "<p class=\"page-lead\">" (esc (m key)) "</p>"))

(defn- section-title-html [key]
  (str "<h2 class=\"section-title\">" (esc (m key)) "</h2>"))

(defn- period-options []
  [["today" (m :daily-today)]
   ["week" (m :daily-week)]
   ["last_week" (m :daily-last-week)]
   ["around7" (m :daily-around7)]
   ["all" (m :daily-all)]])

(defn- daily-filter-section [state]
  (let [range (current-daily-range state)]
    (str "<section class=\"form-section\" id=\"daily-filter-section\">"
         (section-title-html :daily-section-filter)
         "<div class=\"toolbar\" id=\"daily-range-form\">"
         (select-switch (m :daily-range-label) "daily-range" range (period-options))
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
         "</section>")))

(defn- daily-exec-label [status]
  (case (str status)
    "in_progress" (m :exec-in-progress)
    "done" (m :exec-done)
    (m :exec-not-started)))

(defn- daily-row-html [state r readonly?]
  (let [admin? (= "admin" (:kind state))]
    (str "<div class=\"daily-item\" id=\"daily-item-" (esc (:id r)) "\">"
         (when (and admin? (not (str/blank? (str (:user_email r)))))
           (str "<p class=\"daily-item-user\">" (esc (:user_email r)) "</p>"))
         (when (true? (:overdue r))
           (str "<span class=\"daily-item-overdue\">" (esc (m :daily-overdue)) "</span> "))
         (when (and (= "done" (:execution_status r))
                    (pos? (checklist-left (:checklist_done r) (:checklist_total r))))
           (str "<span class=\"daily-item-checklist-left\">" (esc (m :daily-checklist-left)) "</span> "))
         "<span class=\"daily-item-title\">" (esc (:title r)) "</span>"
         " <span class=\"daily-item-time\">"
         (esc (format-display-instant (:start_at r)))
         "〜"
         (esc (format-display-instant (:end_at r))) "</span>"
         "<p class=\"daily-item-summary\">"
         "<span>" (esc (daily-work-time-summary (:work_time_count r))) "</span>"
         (when (pos? (or (:work_time_today r) 0))
           (str " <span class=\"daily-item-today-time\">" (esc (m :daily-work-time-today)) "</span>"))
         " / "
         "<span>" (esc (daily-checklist-summary (:checklist_done r)
                                                (:checklist_total r)))
         "</span>"
         (when-not readonly?
           (str " · <a data-nav href=\"/works?id=" (esc (:id r)) "\">"
                (esc (m :daily-link-edit-work)) "</a>"))
         "</p>"
         (if readonly?
           (str "<p class=\"daily-item-status\">" (esc (m :execution-status)) ": "
                (esc (daily-exec-label (:execution_status r))) "</p>")
           (str "<form data-act=\"set-daily-row-status\" method=\"post\" class=\"daily-status-form\">"
                "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                "<label>" (esc (m :execution-status))
                (execution-status-select-html (:execution_status r)
                                              (str "daily-status-" (:id r)))
                "</label>"
                "<button type=\"submit\">" (esc (m :btn-save)) "</button>"
                "</form>"))
         "</div>")))

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

(defn- current-works-range [state]
  (let [r (str (:works-range state))]
    (if (contains? daily-ranges r) r works-default-range)))

(defn- works-status-on? [state status]
  (boolean (some #(= (str %) status) (:works-statuses state))))

(defn- works-now [state]
  (some-> (:gantt-windows state) :now str))

(defn- works-row-overdue? [now r]
  (boolean (and now
                (not= "done" (str (:execution_status r)))
                (not (pos? (compare (str (:end_at r)) now))))))

(defn- works-visible-rows
  "日次一覧と同じ規則（期間と重なる作業、現在を含む期間では遅れも）で読み込み済みの作業を絞る。
  期間が届いていなければ期間では絞らない。"
  [state]
  (let [range (current-works-range state)
        win (get (:gantt-windows state) (keyword range))
        now (works-now state)]
    (filterv (fn [r]
               (and (works-status-on? state (str (or (:execution_status r) "not_started")))
                    (or (= "all" range)
                        (not (sequential? win))
                        (let [[w0 w1] (map str win)]
                          (or (and (pos? (compare w1 (str (:start_at r))))
                                   (pos? (compare (str (:end_at r)) w0)))
                              (and (pos? (compare w1 (str now)))
                                   (works-row-overdue? now r)))))))
             (or (:gantt-rows state) []))))

(defn- works-filter-html [state]
  (let [range (current-works-range state)]
    (str "<form data-act=\"set-works-filter\" method=\"post\" id=\"works-filter\">"
         "<label>" (esc (m :daily-range-label))
         "<select name=\"range\" id=\"works-range\">"
         (apply str
                (for [[v lab] (period-options)]
                  (str "<option value=\"" v "\"" (when (= v range) " selected") ">"
                       (esc lab) "</option>")))
         "</select></label>"
         "<fieldset><legend>" (esc (m :execution-status)) "</legend>"
         (apply str
                (for [[v lab] [["not_started" (m :exec-not-started)]
                               ["in_progress" (m :exec-in-progress)]
                               ["done" (m :exec-done)]]]
                  (str "<label><input type=\"checkbox\" name=\"status\" value=\"" v "\""
                       (when (works-status-on? state v) " checked")
                       "> " (esc lab) "</label>")))
         "</fieldset>"
         "<button type=\"submit\" id=\"works-filter-btn\">"
         (esc (m :daily-filter-apply)) "</button></form>")))

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
                         visible (works-visible-rows state)
                         now (works-now state)
                         sel (gantt-row-by-id state (:gantt-selected state))]
                     (str
                      (page-lead-html :works-lead)
                      "<section class=\"works-list form-section\" id=\"works-list\">"
                      (section-title-html :works-section-list)
                      (when (seq rows) (works-filter-html state))
                      (cond
                        (empty? rows)
                        (str "<p class=\"empty-hint\">" (esc (m :works-empty)) "</p>")
                        (empty? visible)
                        (str "<p class=\"empty-hint\" id=\"works-filter-empty\">"
                             (esc (m :works-filter-empty)) "</p>")
                        :else
                        (apply str
                               (for [r visible]
                                 (let [selected? (same-gantt-id? (:id r) (:gantt-selected state))]
                                   (str "<form class=\"work-item" (when selected? " selected")
                                        "\" data-act=\"select-gantt-row\" method=\"post\">"
                                        "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id r)) "\">"
                                        "<button type=\"submit\" class=\"work-open-btn\" id=\"work-btn-"
                                        (esc (:id r)) "\">"
                                        (esc (m :works-open-prefix)) (esc (:title r))
                                        (when selected? (esc (m :works-open-selected)))
                                        "</button>"
                                        "<p class=\"work-item-meta\">"
                                        (when (works-row-overdue? now r)
                                          (str "<span class=\"daily-item-overdue\">"
                                               (esc (m :daily-overdue)) "</span> "))
                                        (esc (work-related-title-label state (:title_id r)))
                                        " · " (esc (format-display-instant (:start_at r)))
                                        "〜" (esc (format-display-instant (:end_at r)))
                                        " · " (esc (execution-status-label (:execution_status r)))
                                        "</p></form>")))))
                      "</section>"
                      (cond
                        (nil? sel)
                        (when (seq rows)
                          (str "<aside class=\"pick-hint\" id=\"works-pick-hint\">"
                               "<p>" (esc (m :works-pick-hint)) "</p></aside>"))

                        :else
                        (str
                         "<section class=\"form-section editing-panel\" id=\"works-edit-section\">"
                         "<p class=\"editing-banner\" id=\"works-editing-banner\">"
                         (esc (m :works-editing-prefix)) (esc (:title sel)) "</p>"
                         (flash-at state "works-edit-section")
                         (section-title-html :works-section-edit)
                         "<p class=\"section-lead\">" (esc (m :works-basics-lead)) "</p>"
                         (flash-at state "works-save-form")
                         "<form data-act=\"save-gantt-row\" method=\"post\" id=\"works-save-form\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                         "<label>" (esc (m :gantt-title-of-work))
                         (gantt-title-select-html titles (:title_id sel) true "works-save-title-id") "</label>"
                         "<label>" (esc (m :gantt-title-label))
                         "<input name=\"title\" value=\"" (esc (:title sel)) "\" required>"
                         (field-hint (m :gantt-title-hint)) "</label>"
                         "<label>" (esc (m :gantt-start))
                         "<input type=\"datetime-local\" name=\"start_at\" value=\"" (esc (:start_at sel)) "\" required></label>"
                         "<label>" (esc (m :gantt-end))
                         "<input type=\"datetime-local\" name=\"end_at\" value=\"" (esc (:end_at sel)) "\" required></label>"
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
                         "<button type=\"submit\" id=\"works-save-btn\" class=\"btn-primary\">"
                         (esc (m :works-basics-save)) "</button></form>"
                         (gantt-children-edit-html state (:id sel) "works")
                         "<div class=\"danger-zone\" id=\"works-delete-section\">"
                         (section-title-html :works-section-delete)
                         "<p class=\"section-lead\">" (esc (m :works-delete-lead)) "</p>"
                         "<form data-act=\"delete-gantt-row\" method=\"post\" id=\"works-delete-form\""
                         " data-confirm=\"" (esc (m :gantt-delete-confirm)) "\">"
                         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                         "<button type=\"submit\" id=\"works-delete-btn\">"
                         (esc (m :gantt-row-delete)) "</button></form>"
                         "</div>"
                         "</section>"))
                      "<section class=\"form-section\" id=\"works-add-section\">"
                      (section-title-html :works-section-add)
                      (flash-at state "works-add-form")
                      "<form data-act=\"add-gantt-row\" method=\"post\" id=\"works-add-form\">"
                      "<label>" (esc (m :gantt-title-of-work))
                      (gantt-title-select-html titles nil true "works-add-title-id") "</label>"
                      "<label>" (esc (m :gantt-title-label))
                      "<input id=\"works-new-title\" name=\"title\" value=\"" (esc (:title defs))
                      "\" placeholder=\"" (esc (m :gantt-work-new-placeholder)) "\">"
                      (field-hint (m :gantt-title-hint)) "</label>"
                      "<label>" (esc (m :gantt-start))
                      "<input type=\"datetime-local\" id=\"works-new-start\" name=\"start_at\" value=\"" (esc (:start defs))
                      "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<label>" (esc (m :gantt-end))
                      "<input type=\"datetime-local\" id=\"works-new-end\" name=\"end_at\" value=\"" (esc (:end defs))
                      "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                      "<label>" (esc (m :work-name))
                      "<input id=\"works-new-work-name\" name=\"work_name\" list=\"works-add-work-name-list\" value=\"\">"
                      "<datalist id=\"works-add-work-name-list\">"
                      (apply str (for [nm (:work-names state)]
                                   (str "<option value=\"" (esc nm) "\">")))
                      "</datalist>"
                      (field-hint (m :work-name-hint)) "</label>"
                      "<p class=\"field-hint\">" (esc (m :execution-status)) ": "
                      (esc (m :exec-not-started)) "</p>"
                      "<button type=\"submit\" id=\"works-add-btn\" class=\"btn-primary\">"
                      (esc (m :gantt-work-add)) "</button></form>"
                      "</section>")))))))

(defn daily-view [state]
  (let [admin? (= "admin" (:kind state))
        narrow? (boolean (:narrow? state))
        fields (:fields state)
        statuses (into [] (:daily-statuses state))
        rows (into [] (:daily-rows state))
        total (or (:daily-total state) 0)
        readonly? (boolean (or narrow? admin?))
        nav (if admin? (nav-admin state) (nav-user state))]
    (cond
      (and admin? (not narrow?))
      (layout (m :daily-title)
              (str nav (flash-html state)
                   "<p>" (esc (m :phone-daily-admin-pc)) "</p>"))

      (and (not admin?) (empty? fields))
      (layout (m :daily-title)
              (str nav (flash-html state)
                   "<p>" (esc (m :daily-no-fields)) "</p>"))

      :else
      (layout (m :daily-title)
              (str nav
                   (flash-html state)
                   (when narrow?
                     (str "<p class=\"field-hint\" id=\"daily-readonly-hint\">"
                          (esc (m :daily-phone-readonly)) "</p>"))
                   (page-lead-html :daily-lead)
                   (daily-filter-section state)
                   (if (empty? statuses)
                     (str "<p id=\"daily-filter-hint\">" (esc (m :daily-filter-empty)) "</p>")
                     (str "<section class=\"daily-list form-section\" id=\"daily-list\">"
                          (section-title-html :daily-section-list)
                          (flash-at state "daily-list")
                          (if (empty? rows)
                            (if (pos? total)
                              (str "<p class=\"empty-hint\">" (esc (m :daily-empty-filtered)) "</p>"
                                   (when-not readonly?
                                     (str "<p><a data-nav href=\"/works\">"
                                          (esc (m :daily-link-works)) "</a></p>")))
                              (str "<p class=\"empty-hint\">" (esc (m :daily-empty)) "</p>"
                                   (when-not readonly?
                                     (str "<p><a data-nav href=\"/works\">"
                                          (esc (m :daily-link-works)) "</a></p>"))))
                            (apply str (for [r rows]
                                         (daily-row-html state r readonly?))))
                          "</section>")))))))

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
                      (flash-at state "gantt-titles")
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
                                           "<button type=\"submit\">"
                                           (esc (m :gantt-open-prefix)) (esc (:title r))
                                           (when selected? (esc (m :works-open-selected)))
                                           "<span class=\"work-item-meta\"> ("
                                           (esc (format-display-instant (:start_at r)))
                                           "〜"
                                           (esc (format-display-instant (:end_at r))) ")"
                                           " [" (esc (execution-status-label (:execution_status r))) "]"
                                           "</span></button>"
                                           "<div class=\"gantt-bar\"></div></form>")))))
                         "</div>"
                         "</div>"
                         "</div>"
                         (when (and (seq title-rows)
                                    (or (nil? sel)
                                        (not (same-gantt-id? (:title_id sel) (:id title-sel)))))
                           (str "<aside class=\"pick-hint\" id=\"gantt-pick-hint\">"
                                "<p>" (esc (m :gantt-pick-hint)) "</p></aside>"))
                         (when (and sel (same-gantt-id? (:title_id sel) (:id title-sel)))
                           (str
                            "<section class=\"form-section editing-panel\" id=\"gantt-edit-section\">"
                            "<p class=\"editing-banner\" id=\"gantt-editing-banner\">"
                            (esc (m :works-editing-prefix)) (esc (:title sel)) "</p>"
                            (flash-at state "gantt-edit-section")
                            (section-title-html :gantt-section-edit)
                            "<p class=\"section-lead\">" (esc (m :works-basics-lead)) "</p>"
                            (flash-at state "gantt-save-form")
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
                            "<input type=\"datetime-local\" name=\"start_at\" value=\"" (esc (:start_at sel)) "\" required></label>"
                            "<label>" (esc (m :gantt-end))
                            "<input type=\"datetime-local\" name=\"end_at\" value=\"" (esc (:end_at sel)) "\" required></label>"
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
                            "<button type=\"submit\" id=\"gantt-save-btn\" class=\"btn-primary\">"
                            (esc (m :works-basics-save)) "</button></form>"
                            (gantt-children-edit-html state (:id sel) "gantt")
                            "<div class=\"danger-zone\" id=\"gantt-delete-section\">"
                            (section-title-html :works-section-delete)
                            "<p class=\"section-lead\">" (esc (m :works-delete-lead)) "</p>"
                            "<form data-act=\"delete-gantt-row\" method=\"post\" id=\"gantt-delete-form\""
                            " data-confirm=\"" (esc (m :gantt-delete-confirm)) "\">"
                            "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                            "<button type=\"submit\" id=\"gantt-delete-btn\">"
                            (esc (m :gantt-row-delete)) "</button></form>"
                            "<form data-act=\"review-gantt-row\" method=\"post\" id=\"gantt-review-form\">"
                            "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id sel)) "\">"
                            "<button type=\"submit\" id=\"gantt-review-btn\">"
                            (esc (m :gantt-review)) "</button></form>"
                            "</div>"
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
                         "<section class=\"form-section\" id=\"gantt-add-section\">"
                         (section-title-html :gantt-section-add)
                         (flash-at state "gantt-add-form")
                         "<form data-act=\"add-gantt-row\" method=\"post\" id=\"gantt-add-form\">"
                         "<input type=\"hidden\" name=\"title_id\" value=\"" (esc (:id title-sel)) "\">"
                         "<label>" (esc (m :gantt-title-label))
                         "<input id=\"gantt-new-title\" name=\"title\" value=\"" (esc (:title defs))
                         "\" placeholder=\"" (esc (m :gantt-work-new-placeholder)) "\">"
                         (field-hint (m :gantt-title-hint)) "</label>"
                         "<label>" (esc (m :gantt-start))
                         "<input type=\"datetime-local\" id=\"gantt-new-start\" name=\"start_at\" value=\"" (esc (:start defs))
                         "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                         "<label>" (esc (m :gantt-end))
                         "<input type=\"datetime-local\" id=\"gantt-new-end\" name=\"end_at\" value=\"" (esc (:end defs))
                         "\" placeholder=\"YYYY-MM-DDTHH:MM\"></label>"
                         "<label>" (esc (m :work-name))
                         "<input id=\"gantt-new-work-name\" name=\"work_name\" list=\"gantt-add-work-name-list\" value=\"\">"
                         "<datalist id=\"gantt-add-work-name-list\">"
                         (apply str (for [nm (:work-names state)]
                                      (str "<option value=\"" (esc nm) "\">")))
                         "</datalist>"
                         (field-hint (m :work-name-hint)) "</label>"
                         "<button type=\"submit\" id=\"gantt-add-btn\" class=\"btn-primary\">"
                         (esc (m :gantt-work-add)) "</button></form>"
                         "</section>"
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

(defn- split-lines-csv [s]
  (->> (str/split (str s) #"[\n,]+")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- memo-page? [page]
  (contains? #{:memos :memos-drafts :memos-bookmarks} page))

(defn- memo-top-nav [state]
  (if (= "admin" (:kind state)) (nav-admin state) (nav-user state)))

(defn- memo-list-near [state]
  (case (:page state)
    :memos-drafts "memo-drafts-list"
    :memos-bookmarks "memo-bookmarks-list"
    "memo-timeline"))

(defn- memo-nav [state]
  (str "<nav class=\"memo-nav\" id=\"memo-nav\">"
       (apply str
              (for [[page href key] [[:memos "/memos" :memos-timeline]
                                     [:memos-drafts "/memos/drafts" :memos-drafts]
                                     [:memos-bookmarks "/memos/bookmarks" :memos-bookmarks]]]
                (str "<a data-nav href=\"" href "\""
                     (when (= page (:page state)) " class=\"current\"")
                     ">" (esc (m key)) "</a>")))
       "</nav>"))

(defn- memo-by-id [state id]
  (let [sid (str id)]
    (or (some (fn [x] (when (= sid (str (:id x))) x))
              (concat (:memos state) (:memo-replies state)
                      (:memo-drafts state) (:memo-bookmarks state)
                      (:memo-search-results state)))
        (let [m (:memo-last-saved state)]
          (if (and m (= sid (str (:id m)))) m nil)))))

(defn- memo-selected-memo
  "開いているスレッドの先頭。取り直した1件があればそれを使う。"
  [state]
  (let [sid (str (:memo-selected state))
        row (:memo-selected-row state)]
    (cond
      (str/blank? sid) nil
      (and row (= sid (str (:id row)))) row
      :else (memo-by-id state sid))))

(defn- memo-code-message [code]
  (if (= "body_too_long" (str code))
    (m :memos-body-limit)
    (code-message code)))

(defn- memo-draft? [memo]
  (= "draft" (str (:status memo))))

(defn- memo-meta-html [memo]
  (str "<p class=\"memo-meta\">"
       (esc (:author_email memo))
       (when-not (str/blank? (str (:published_at memo)))
         (str " · " (esc (format-display-instant (:published_at memo)))))
       (when (memo-draft? memo)
         (str " · " (esc (m :memos-draft-label))))
       (when-not (str/blank? (str (:editable_until memo)))
         (str " · " (esc (m :memos-edit-until))
              (esc (format-display-instant (:editable_until memo)))))
       "</p>"))

(defn- memo-tags-html [memo]
  (when (seq (:tags memo))
    (str "<p class=\"memo-tags\">"
         (esc (str/join " " (map (fn [t] (str "#" t)) (:tags memo))))
         "</p>")))

(defn- memo-links-html [memo]
  (when (seq (:links memo))
    (str "<ul class=\"memo-links\">"
         (apply str
                (for [u (:links memo)]
                  (str "<li><a href=\"" (esc u) "\" target=\"_blank\" rel=\"noreferrer noopener\">"
                       (esc u) "</a></li>")))
         "</ul>")))

(defn- memo-attachments-html [memo]
  (when (seq (:attachments memo))
    (str "<ul class=\"memo-attachments\">"
         (apply str
                (for [a (:attachments memo)]
                  (str "<li><a href=\"/api/memos/" (esc (:id memo))
                       "/attachments/" (esc (:id a)) "\">"
                       (esc (:filename a)) "</a>"
                       (if (:can_edit memo)
                         (str " <form data-act=\"memo-detach\" method=\"post\" class=\"inline\">"
                              "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
                              "<input type=\"hidden\" name=\"attachment_id\" value=\"" (esc (:id a)) "\">"
                              "<button type=\"submit\">" (esc (m :memos-detach)) "</button></form>")
                         "")
                       "</li>")))
         "</ul>")))

(defn- memo-attach-form [memo]
  (if-not (:can_edit memo)
    ""
    (str "<form data-act=\"memo-attach\" method=\"post\" enctype=\"multipart/form-data\" class=\"memo-attach\">"
         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
         "<input type=\"file\" name=\"file\" data-auto-upload=\"1\" aria-label=\""
         (esc (m :memos-attach)) "\">"
         "<span class=\"memo-upload-status\" aria-live=\"polite\"></span>"
         "</form>")))

(defn- memo-edit-form [memo]
  (if-not (:can_edit memo)
    ""
    (str "<div class=\"memo-edit\">"
         "<p class=\"memo-edit-title\">" (esc (m :memos-edit)) "</p>"
         "<form data-act=\"memo-update\" method=\"post\" class=\"memo-edit-form\">"
         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
         "<label>" (esc (m :memos-body-label))
         "<textarea name=\"body\" rows=\"3\">" (esc (:body memo)) "</textarea></label>"
         "<label>" (esc (m :memos-tags-label))
         "<textarea name=\"tags\" rows=\"2\">"
         (esc (str/join "\n" (or (:tags memo) []))) "</textarea></label>"
         "<label>" (esc (m :memos-links-label))
         "<textarea name=\"links\" rows=\"2\">"
         (esc (str/join "\n" (or (:links memo) []))) "</textarea></label>"
         "<button type=\"submit\">" (esc (m :btn-save)) "</button></form>"
         (memo-attach-form memo)
         "</div>")))

(defn- memo-star-form [memo]
  (let [on? (boolean (:bookmarked memo))]
    (str "<form data-act=\"" (if on? "memo-unbookmark" "memo-bookmark")
         "\" method=\"post\" class=\"inline\">"
         "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
         "<button type=\"submit\" class=\"memo-star\" title=\""
         (esc (if on? (m :memos-bookmark-remove) (m :memos-bookmark-add))) "\">"
         (esc (if on? (m :memos-star-on) (m :memos-star-off)))
         "</button></form>")))

(defn- memo-select-form [memo]
  (str "<form data-act=\"memo-select\" method=\"post\" class=\"inline\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
       "<button type=\"submit\">" (esc (m :memos-select-thread))
       (when (pos? (or (:reply_count memo) 0))
         (str "（" (esc (:reply_count memo)) "）"))
       "</button></form>"))

(defn- memo-delete-form [memo]
  (str "<form data-act=\"memo-delete\" method=\"post\" class=\"inline\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
       "<button type=\"submit\">" (esc (m :btn-delete)) "</button></form>"))

(defn- memo-gantt-option-label [row admin?]
  (let [title (str (or (:title row) ""))
        wn (str/trim (str (or (:work_name row) "")))
        email (str (or (:user_email row) ""))
        base (if (or (str/blank? wn) (= wn title))
               title
               (str title "（" wn "）"))]
    (if (and admin? (not (str/blank? email)))
      (str email " — " base)
      base)))

(defn- memo-gantt-summary-html [gantt]
  (when gantt
    (str "<p class=\"memo-gantt-summary\" id=\"memo-gantt-summary\">"
         (esc (m :memos-gantt-label)) ": "
         (esc (or (:title gantt) ""))
         (when (:deleted gantt)
           (str "（" (esc (m :memos-gantt-deleted)) "）"))
         "</p>"
         "<p class=\"field-hint\" id=\"memo-gantt-summary-hint\">"
         (esc (m :memos-gantt-summary-hint)) "</p>")))

(defn- memo-gantt-link-section
  "スレッド詳細の公開済み親のみ。作成者・管理者は付け外し、閲覧者は紐づきありのときだけ要約。"
  [state memo]
  (let [gantt (:gantt memo)
        can? (boolean (:can_link_gantt memo))
        admin? (= "admin" (str (:kind state)))
         cands (vec (or (:memo-gantt-candidates state) []))
         pick (let [p (:memo-gantt-pick state)]
                (if (nil? p)
                  (str (:id gantt))
                  (str p)))]
    (cond
      (and (not can?) gantt)
      (str "<div class=\"memo-gantt-link\" id=\"memo-gantt-link\">"
           (memo-gantt-summary-html gantt)
           "</div>")

      (not can?)
      ""

      :else
      (str "<div class=\"memo-gantt-link\" id=\"memo-gantt-link\">"
           (when gantt (memo-gantt-summary-html gantt))
           (if (empty? cands)
             (str "<p class=\"empty-hint\" id=\"memo-gantt-empty\">"
                  (esc (m :memos-gantt-empty)) "</p>")
             (str "<form data-act=\"memo-gantt-link\" method=\"post\" id=\"memo-gantt-link-form\">"
                  "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
                  "<label>" (esc (m :memos-gantt-select))
                  "<select name=\"gantt_id\" id=\"memo-gantt-select\">"
                  (apply str
                         (for [r cands]
                           (str "<option value=\"" (esc (:id r)) "\""
                                (when (= (str (:id r)) pick) " selected")
                                ">" (esc (memo-gantt-option-label r admin?))
                                "</option>")))
                  "</select></label>"
                  "<button type=\"submit\" id=\"memo-gantt-link-btn\" class=\"btn-primary\">"
                  (esc (m (if gantt :memos-gantt-retarget :memos-gantt-link)))
                  "</button></form>"))
           (when gantt
             (str "<form data-act=\"memo-gantt-unlink\" method=\"post\" class=\"inline\" id=\"memo-gantt-unlink-form\">"
                  "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id memo)) "\">"
                  "<button type=\"submit\" id=\"memo-gantt-unlink-btn\">"
                  (esc (m :memos-gantt-unlink)) "</button></form>"))
           "</div>"))))

(defn- memo-status-fieldset [selected]
  (str "<fieldset class=\"memo-status\"><legend>" (esc (m :memos-compose)) "</legend>"
       (apply str
              (for [[v key] [["published" :memos-publish]
                             ["draft" :memos-save-draft]]]
                (str "<label><input type=\"radio\" name=\"status\" value=\"" v "\""
                     (when (= v (str selected)) " checked")
                     "> " (esc (m key)) "</label>")))
       "</fieldset>"))

(defn- memo-item-html
  "opts: :show-select? タイムライン／検索でスレッドを開くボタンを出す。"
  ([state memo] (memo-item-html state memo {:show-select? false}))
  ([state memo opts]
   (str "<li class=\"memo-item\" id=\"memo-item-" (esc (:id memo)) "\">"
        "<p class=\"memo-body\">" (esc (:body memo)) "</p>"
        (memo-meta-html memo)
        (memo-tags-html memo)
        (memo-links-html memo)
        (memo-attachments-html memo)
        (memo-edit-form memo)
        "<p class=\"memo-actions\">"
        (when-not (memo-draft? memo) (memo-star-form memo))
        (when (:show-select? opts) (memo-select-form memo))
        (when (:can_delete memo) (memo-delete-form memo))
        "</p></li>")))

(defn- memo-compose-section [state]
  (let [c (or (:memo-compose state) {})
        last (let [m (:memo-last-saved state)]
               (if (and m (:can_edit m)) m nil))]
    (str "<section class=\"form-section\" id=\"memo-compose-section\">"
         (section-title-html :memos-compose)
         (flash-at state "memo-compose-section")
         "<form data-act=\"memo-publish-new\" method=\"post\" id=\"memo-compose-form\">"
         "<label>" (esc (m :memos-body-label))
         "<textarea name=\"body\" rows=\"4\">" (esc (:body c)) "</textarea>"
         (field-hint (m :memos-body-limit)) "</label>"
         "<label>" (esc (m :memos-tags-label))
         "<textarea name=\"tags\" rows=\"2\">" (esc (:tags c)) "</textarea></label>"
         "<label>" (esc (m :memos-links-label))
         "<textarea name=\"links\" rows=\"2\">" (esc (:links c)) "</textarea></label>"
         (memo-status-fieldset "published")
         "<p class=\"field-hint\">" (esc (m :memos-attach-after-save)) "</p>"
         "<button type=\"submit\" id=\"memo-compose-btn\" class=\"btn-primary\">"
         (esc (m :btn-save)) "</button></form>"
         (if last
           (str "<div class=\"memo-compose-saved\" id=\"memo-compose-saved\">"
                "<p class=\"memo-body\">" (esc (:body last)) "</p>"
                (memo-meta-html last)
                (memo-attachments-html last)
                (memo-edit-form last)
                "</div>")
           "")
         "</section>")))

(defn- memo-search-section [state]
  (let [adv? (boolean (:memo-search-advanced? state))
        f (or (:memo-search-form state) {})
        active? (boolean (:memo-search-active? state))
        rows (vec (or (:memo-search-results state) []))]
    (str "<section class=\"form-section\" id=\"memo-search-section\">"
         (section-title-html :memos-search)
         (flash-at state "memo-search-section")
         "<form data-act=\"memo-search\" method=\"post\" id=\"memo-search-form\">"
         "<label>" (esc (m :memos-search))
         "<input name=\"q\" value=\"" (esc (:memo-search-q state)) "\"></label>"
         (when adv?
           (str "<div class=\"memo-search-advanced\" id=\"memo-search-advanced\">"
                "<label>" (esc (m :memos-exclude))
                "<input name=\"exclude\" value=\"" (esc (:exclude f)) "\"></label>"
                "<label>" (esc (m :memos-from))
                "<input name=\"from\" value=\"" (esc (:from f)) "\" placeholder=\"YYYY-MM-DD\"></label>"
                "<label>" (esc (m :memos-to))
                "<input name=\"to\" value=\"" (esc (:to f)) "\" placeholder=\"YYYY-MM-DD\"></label>"
                "<label>" (esc (m :memos-author))
                "<input name=\"author\" value=\"" (esc (:author f)) "\"></label>"
                "</div>"))
         "<button type=\"submit\" id=\"memo-search-btn\" class=\"btn-primary\">"
         (esc (m :memos-search-run)) "</button></form>"
         "<p class=\"memo-search-actions\">"
         "<form data-act=\"memo-search-advanced-toggle\" method=\"post\" class=\"inline\">"
         "<button type=\"submit\" id=\"memo-search-advanced-btn\">"
         (esc (m (if adv? :memos-advanced-close :memos-advanced-open)))
         "</button></form>"
         (when active?
           (str "<form data-act=\"memo-search-clear\" method=\"post\" class=\"inline\">"
                "<button type=\"submit\" id=\"memo-search-clear-btn\">"
                (esc (m :memos-search-clear)) "</button></form>"))
         "</p>"
         (when active?
           (str "<div class=\"memo-search-results\" id=\"memo-search-results\">"
                "<p class=\"memo-search-query\">"
                (esc (m :memos-search-query-prefix))
                (esc (or (:memo-search-q state) ""))
                (esc (m :memos-search-query-suffix))
                "</p>"
                "<h3>" (esc (m :memos-search-results)) "</h3>"
                (if (empty? rows)
                  (str "<p class=\"empty-hint\">" (esc (m :memos-search-empty)) "</p>")
                  (str "<ul class=\"memo-list\">"
                       (apply str (for [r rows]
                                    (memo-item-html state r {:show-select? true})))
                       "</ul>"))
                "</div>"))
         "</section>")))

(defn- memo-thread-section [state]
  (when-let [sel (memo-selected-memo state)]
    (str "<section class=\"form-section\" id=\"memo-thread-section\">"
         (section-title-html :memos-thread)
         (flash-at state "memo-thread-section")
         "<form data-act=\"memo-close-thread\" method=\"post\" class=\"inline\">"
         "<button type=\"submit\" id=\"memo-close-thread-btn\">"
         (esc (m :memos-close-thread)) "</button></form>"
         "<ul class=\"memo-list memo-thread\">"
         (memo-item-html state sel)
         "</ul>"
         (memo-gantt-link-section state sel)
         "<ul class=\"memo-list memo-thread memo-replies\">"
         (apply str (for [r (:memo-replies state)] (memo-item-html state r)))
         "</ul>"
         "<form data-act=\"memo-reply\" method=\"post\" id=\"memo-reply-form\">"
         "<input type=\"hidden\" name=\"parent_id\" value=\"" (esc (:id sel)) "\">"
         "<label>" (esc (m :memos-body-label))
         "<textarea name=\"body\" rows=\"3\"></textarea></label>"
         "<label>" (esc (m :memos-tags-label))
         "<textarea name=\"tags\" rows=\"2\"></textarea></label>"
         "<label>" (esc (m :memos-links-label))
         "<textarea name=\"links\" rows=\"2\"></textarea></label>"
         (memo-status-fieldset "published")
         "<p class=\"field-hint\">" (esc (m :memos-attach-after-save)) "</p>"
         "<button type=\"submit\" id=\"memo-reply-btn\" class=\"btn-primary\">"
         (esc (m :memos-reply)) "</button></form>"
         "</section>")))

(defn memos-view [state]
  (let [rows (into [] (:memos state))]
    (layout (m :memos-title)
            (str (memo-top-nav state)
                 (memo-nav state)
                 (flash-html state)
                 (memo-compose-section state)
                 (memo-search-section state)
                 "<section class=\"form-section memo-timeline\" id=\"memo-timeline\">"
                 (section-title-html :memos-timeline)
                 (flash-at state "memo-timeline")
                 (if (empty? rows)
                   (str "<p class=\"empty-hint\">" (esc (m :memos-empty)) "</p>")
                   (str "<ul class=\"memo-list\">"
                        (apply str (for [r rows]
                                     (memo-item-html state r {:show-select? true})))
                        "</ul>"
                        "<form data-act=\"memo-more\" method=\"post\" class=\"inline\">"
                        "<input type=\"hidden\" name=\"before_id\" value=\""
                        (esc (:id (last rows))) "\">"
                        "<button type=\"submit\" id=\"memo-more-btn\">"
                        (esc (m :memos-more)) "</button></form>"))
                 "</section>"
                 (memo-thread-section state)))))

(defn- memo-draft-item-html [d]
  (str "<li class=\"memo-item\" id=\"memo-draft-" (esc (:id d)) "\">"
       "<form data-act=\"memo-update-draft\" method=\"post\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id d)) "\">"
       "<label>" (esc (m :memos-body-label))
       "<textarea name=\"body\" rows=\"3\">" (esc (:body d)) "</textarea></label>"
       "<label>" (esc (m :memos-tags-label))
       "<textarea name=\"tags\" rows=\"2\">" (esc (str/join "\n" (:tags d))) "</textarea></label>"
       "<label>" (esc (m :memos-links-label))
       "<textarea name=\"links\" rows=\"2\">" (esc (str/join "\n" (:links d))) "</textarea></label>"
       "<button type=\"submit\">" (esc (m :btn-save)) "</button></form>"
       (memo-meta-html d)
       (memo-attachments-html d)
       (memo-attach-form d)
       "<p class=\"memo-actions\">"
       "<form data-act=\"memo-publish-draft\" method=\"post\" class=\"inline\">"
       "<input type=\"hidden\" name=\"id\" value=\"" (esc (:id d)) "\">"
       "<button type=\"submit\">" (esc (m :memos-publish)) "</button></form>"
       (memo-delete-form d)
       "</p></li>"))

(defn memos-drafts-view [state]
  (let [rows (into [] (:memo-drafts state))]
    (layout (m :memos-title)
            (str (memo-top-nav state)
                 (memo-nav state)
                 (flash-html state)
                 "<section class=\"form-section\" id=\"memo-drafts-list\">"
                 (section-title-html :memos-drafts)
                 (flash-at state "memo-drafts-list")
                 (if (empty? rows)
                   (str "<p class=\"empty-hint\">" (esc (m :memos-drafts-empty)) "</p>")
                   (str "<ul class=\"memo-list\">"
                        (apply str (for [d rows] (memo-draft-item-html d)))
                        "</ul>"))
                 "</section>"))))

(defn memos-bookmarks-view [state]
  (let [rows (into [] (:memo-bookmarks state))]
    (layout (m :memos-title)
            (str (memo-top-nav state)
                 (memo-nav state)
                 (flash-html state)
                 "<section class=\"form-section\" id=\"memo-bookmarks-list\">"
                 (section-title-html :memos-bookmarks)
                 (flash-at state "memo-bookmarks-list")
                 (if (empty? rows)
                   (str "<p class=\"empty-hint\">" (esc (m :memos-bookmarks-empty)) "</p>")
                   (str "<ul class=\"memo-list\">"
                        (apply str (for [b rows] (memo-item-html state b)))
                        "</ul>"))
                 "</section>"))))

(defn render [state]
  (with-ui-lang state
    (fn []
      (with-bottom-nav
       state
       (if (and (:narrow? state) (contains? #{:fields :map :map-place :works :gantt :orders-new :others}
                                            (:page state)))
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
           :memos (memos-view state)
           :memos-drafts (memos-drafts-view state)
           :memos-bookmarks (memos-bookmarks-view state)
           (unknown-view state)))))))

(defn- split-path-search
  "画面内リンク（data-nav）は href の ? 以降も path として渡ってくるので、ここで分ける。"
  [path search]
  (if-let [i (and (string? path) (str/blank? search) (str/index-of path "?"))]
    [(subs path 0 i) (subs path i)]
    [path (or search "")]))

(defn- works-query-id [search]
  (let [id (str (:id (parse-query search)))]
    (when (re-matches #"\d+" id) id)))

(defn apply-route [state path search]
  (let [[path search] (split-path-search path search)
        r (route-for path)]
    (assoc state
           :path path
           :search search
           :page (:page r)
           :kind (or (:kind r) (:kind state) "user")
           :order-id (:order-id r)
           :initial-password nil)))

(defn guarded [state]
  (cond
    (and (needs-auth? (:page state)) (nil? (:session state)))
    {:state (assoc state :flash {:error? true :text (m :unauthorized)})
     :fx [[:nav (login-path (:kind state))]]}

    (and (= :login (:page state)) (:session state))
    {:state state
     :fx [[:nav (home-after-login state)]]}

    :else
    {:state state
     :fx [[:html (render state)]]}))

(defn boot [state {:keys [path search narrow? ui-lang]}]
  (let [s (apply-route (assoc state
                              :narrow? (boolean narrow?)
                              :ui-lang (normalize-lang (or ui-lang (:ui-lang state) "ja")))
                       path search)
        token (:token (parse-query search))]
    {:state (cond-> (assoc s :form (if token {:token token} {}))
              (= :works (:page s)) (assoc :gantt-selected (works-query-id (:search s))))
     :fx [[:session (:kind s)]]}))

(defn- memos-load-fx
  "いま開いているメモの画面に要る一覧を読み直す。"
  [state]
  (case (:page state)
    :memos-drafts [[:api "GET" "/api/memos/drafts" nil :memo-drafts-loaded]]
    :memos-bookmarks [[:api "GET" "/api/memos/bookmarks" nil :memo-bookmarks-loaded]]
    [[:api "GET" "/api/memos" nil :memos-loaded]]))

(defn- memo-thread-fx
  "開いているスレッドの先頭と返信を読み直す。"
  [state]
  (let [id (str (:memo-selected state))]
    (when (and (= :memos (:page state)) (not (str/blank? id)))
      [[:api "GET" (str "/api/memos/" id) nil :memo-loaded]
       [:api "GET" (str "/api/memos/" id "/replies") nil :memo-replies-loaded]])))

(defn- memo-refresh-fx
  "一覧と、開いているスレッドを読み直す。"
  [state]
  (into (memos-load-fx state) (memo-thread-fx state)))

(defn session-loaded [state body]
  (let [s (if (:ok body)
            (cond-> (assoc state :session {:email (:email body)})
              (contains? body :ui_lang)
              (assoc :ui-lang (normalize-lang (:ui_lang body))))
            (assoc state :session nil))]
    (cond
      (and (= :users (:page s)) (:session s))
      {:state s :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]}

      (and (#{:map :map-place :gantt :others} (:page s)) (:session s)
           (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/place" nil :place-loaded]]}

      (and (= :order (:page s)) (:session s))
      (if (= "admin" (:kind s))
        (guarded (assoc s :order nil :order-map nil
                        :flash {:error? false :text (m :orders-admin-phone)}))
        {:state s :fx [[:api "GET" "/api/user/place" nil :place-loaded]]})

      (and (= :fields (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (and (= :works (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (= :daily (:page s))
      (cond
        (nil? (:session s))
        (guarded s)

        (= "admin" (:kind s))
        (if (:narrow? s)
          (let [statuses (into [] (:daily-statuses s))]
            (if (empty? statuses)
              (guarded (assoc s :daily-rows [] :daily-total nil))
              {:state s
               :fx [[:api "GET" (daily-query-path s) nil :daily-loaded]]}))
          (guarded s))

        :else
        {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]})

      (and (= :orders (:page s)) (:session s))
      (if (= "admin" (:kind s))
        (guarded (assoc s :orders-sent [] :orders-received []
                        :flash {:error? false :text (m :orders-admin-phone)}))
        {:state s :fx [[:api "GET" "/api/user/orders" nil :orders-loaded]
                       [:api "GET" "/api/user/fields" nil :home-fields-loaded]]})

      (and (= :orders-new (:page s)) (:session s) (not (:narrow? s)))
      {:state s :fx [[:api "GET" "/api/user/fields" nil :fields-loaded]]}

      (and (= :others (:page s)) (:session s) (:narrow? s))
      (guarded s)

      (memo-page? (:page s))
      (if (:session s)
        {:state s :fx (memos-load-fx s)}
        ;; 経路が利用者・管理者で同じなので、直接開いたときは管理者の入場も試す。
        (if (= "user" (:kind s))
          (if-not (:memo-admin-tried? s)
            {:state (assoc s :kind "admin" :memo-admin-tried? true)
             :fx [[:session "admin"]]}
            (guarded (assoc s :kind "user" :memo-admin-tried? nil)))
          (guarded (assoc s :kind "user" :memo-admin-tried? nil))))

      (= :home (:page s))
      (cond
        (nil? (:session s))
        (guarded s)

        (:narrow? s)
        {:state s :fx [[:api "GET" "/api/memos" nil :memos-loaded]]}

        (= "user" (:kind s))
        {:state s :fx [[:api "GET" "/api/user/fields" nil :home-fields-loaded]]}

        :else
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
                   :gantt-work-times [] :gantt-checklist-items []
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
          works? (= :works (:page state))
          sel' (cond
                 (nil? sel) nil
                 works? (when (some #(same-gantt-id? (:id %) sel) rows) sel)
                 (and tsel' (gantt-row-under-title? rows sel tsel')) sel
                 :else nil)
          row (when sel' (gantt-row-by-id (assoc state :gantt-rows rows) sel'))
          s (assoc state :gantt-rows rows :gantt-titles titles
                   :gantt-windows (:windows body)
                   :gantt-title-selected (if works? (or (:title_id row) tsel') tsel')
                   :gantt-selected sel'
                   :gantt-progress-days (when sel' (:gantt-progress-days state))
                   :gantt-work-times (if sel' (:gantt-work-times state) [])
                   :gantt-checklist-items (if sel' (:gantt-checklist-items state) []))
          child-fx (when sel' (gantt-children-load-fx (:id row)))]
      (cond
        (gantt-row-applicable? row)
        {:state (assoc s :gantt-progress nil)
         :fx (into [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]
                   child-fx)}
        (seq child-fx)
        {:state (assoc s :gantt-progress nil) :fx (vec child-fx)}
        :else
        (guarded (assoc s :gantt-progress nil))))))

(defn gantt-save-result [state body]
  (if (:ok body)
    (let [row (:row body)
          id (:id row)
          tid (:title_id row)
          added? (not (same-gantt-id? (:gantt-selected state) id))
          text (if added?
                 (m :gantt-row-added)
                 (with-checklist-left (m :gantt-row-saved) (:checklist-left state)))
          near (if added?
                 (if (= :gantt (:page state)) "gantt-add-form" "works-add-form")
                 (if (= :gantt (:page state)) "gantt-save-form" "works-save-form"))
          s (flash-ok-state (-> state
                                (dissoc :checklist-left)
                                (assoc :gantt-selected id :gantt-title-selected tid
                                       :gantt-progress-days nil))
                            text near)]
      {:state s
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})
    (let [code (:code body)
          text (if (= "work_name_required" code)
                 (m :gantt-work-needed)
                 (code-message code))
          near (if (= :gantt (:page state)) "gantt-save-form" "works-save-form")
          s (-> state
                (dissoc :checklist-left)
                (assoc :flash {:error? true :text text :near near}))]
      (if (= "gantt_conflict" code)
        {:state s :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]]}
        {:state s :fx [[:html (render s)]]}))))

(defn daily-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :daily-rows (or (:rows body) [])))
    (let [s (assoc state :daily-rows []
                   :flash {:error? true :text (code-message (:code body)) :near "daily-list"})]
      {:state s :fx [[:html (render s)]]})))

(defn daily-context-loaded [state body]
  (let [total (if (:ok body)
                (count (or (:rows body) []))
                (or (:daily-total state) 0))
        s (assoc state :daily-total total)]
    (guarded s)))

(defn daily-status-save-result [state body]
  (if (:ok body)
    (let [statuses (into [] (:daily-statuses state))
          s (flash-ok-state (dissoc state :checklist-left)
                            (with-checklist-left (m :daily-status-saved) (:checklist-left state))
                            "daily-list")]
      (if (empty? statuses)
        (guarded (assoc s :daily-rows []))
        {:state s
         :fx [[:api "GET" (daily-query-path s) nil :daily-loaded]]}))
    (let [s (-> state
                (dissoc :checklist-left)
                (assoc :flash {:error? true :text (code-message (:code body)) :near "daily-list"}))]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-delete-result [state body]
  (if (:ok body)
    (let [near (if (= :gantt (:page state)) "gantt-edit-section" "works-edit-section")]
      {:state (assoc state :gantt-selected nil :gantt-progress nil :gantt-progress-days nil
                     :gantt-work-times [] :gantt-checklist-items []
                     :flash {:error? false :text (m :gantt-deleted) :near near})
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]
            [:api "GET" "/api/user/work-name-candidates" nil :work-names-loaded]]})
    (let [near (if (= :gantt (:page state)) "gantt-edit-section" "works-edit-section")
          s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
      {:state s :fx [[:html (render s)]]})))

(defn work-times-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :gantt-work-times (or (:work_times body) [])))
    (let [near (children-near state "-work-times")
          s (assoc state :gantt-work-times []
                   :flash {:error? true :text (code-message (:code body)) :near near})]
      {:state s :fx [[:html (render s)]]})))

(defn checklist-items-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :gantt-checklist-items (or (:checklist_items body) [])))
    (let [near (children-near state "-checklist")
          s (assoc state :gantt-checklist-items []
                   :flash {:error? true :text (code-message (:code body)) :near near})]
      {:state s :fx [[:html (render s)]]})))

(defn work-time-save-result [state body]
  (let [near (or (:pending-flash-near state) (children-near state "-work-times"))
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [gid (or (get-in body [:work_time :gantt_id]) (:gantt-selected state))
            s (flash-ok-state state (m :work-time-saved) near)]
        {:state s :fx (gantt-children-load-fx gid)})
      (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn work-time-delete-result [state body]
  (if (:ok body)
    (let [near (children-near state "-work-times")
          s (flash-ok-state state (m :work-time-deleted) near)]
      {:state s :fx (gantt-children-load-fx (:gantt-selected state))})
    (let [near (children-near state "-work-times")
          s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
      {:state s :fx [[:html (render s)]]})))

(defn checklist-item-save-result [state body]
  (let [near (or (:pending-flash-near state) (children-near state "-checklist"))
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [gid (or (get-in body [:checklist_item :gantt_id]) (:gantt-selected state))
            s (flash-ok-state state (m :checklist-item-saved) near)]
        {:state s :fx (gantt-children-load-fx gid)})
      (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn checklist-item-delete-result [state body]
  (if (:ok body)
    (let [near (children-near state "-checklist")
          s (flash-ok-state state (m :checklist-deleted) near)]
      {:state s :fx (gantt-children-load-fx (:gantt-selected state))})
    (let [near (children-near state "-checklist")
          s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-title-save-result [state body]
  (if (:ok body)
    (let [tid (get-in body [:title :id])
          s (flash-ok-state (assoc state :gantt-title-selected tid)
                            (m :gantt-title-saved)
                            "gantt-titles")]
      {:state s
       :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near "gantt-titles"})]
      {:state s :fx [[:html (render s)]]})))

(defn gantt-title-delete-result [state body]
  (if (:ok body)
    {:state (assoc state :gantt-title-selected nil :gantt-selected nil
                   :gantt-work-times [] :gantt-checklist-items []
                   :gantt-progress nil :gantt-progress-days nil
                   :flash {:error? false :text (m :gantt-title-deleted) :near "gantt-titles"})
     :fx [[:api "GET" "/api/user/gantt" nil :gantt-loaded]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near "gantt-titles"})]
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
          near (if (= :orders-new (:page state)) "orders-new-section" nil)
          s (assoc state :flash (cond-> {:error? true :text text}
                                  near (assoc :near near)))]
      {:state s :fx [[:html (render s)]]})))

(defn journal-save-result [state body]
  (if (:ok body)
    (let [s (assoc state :order (dissoc body :ok)
                   :flash {:error? false :text (m :journal-saved)
                           :near "order-journal-form-section"})]
      {:state s :fx [[:api "GET" (str "/api/user/orders/" (:id body)) nil :order-loaded]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))
                                 :near "order-journal-form-section"})]
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

(defn memos-loaded [state body]
  (let [append? (not (str/blank? (str (:memo-before-id state))))
        rows (vec (or (:memos body) []))]
    (if (:ok body)
      (guarded (assoc state
                      :memos (if append? (into (vec (:memos state)) rows) rows)
                      :memo-before-id nil))
      (let [s (assoc state
                     :memos (if append? (vec (:memos state)) [])
                     :memo-before-id nil
                     :flash {:error? true :text (code-message (:code body)) :near "memo-timeline"})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-drafts-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :memo-drafts (vec (or (:memos body) []))))
    (let [s (assoc state :memo-drafts []
                   :flash {:error? true :text (code-message (:code body)) :near "memo-drafts-list"})]
      {:state s :fx [[:html (render s)]]})))

(defn memo-bookmarks-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :memo-bookmarks (vec (or (:memos body) []))))
    (let [s (assoc state :memo-bookmarks []
                   :flash {:error? true :text (code-message (:code body)) :near "memo-bookmarks-list"})]
      {:state s :fx [[:html (render s)]]})))

(defn memo-replies-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :memo-replies (vec (or (:replies body) []))))
    (let [s (assoc state :memo-replies []
                   :flash {:error? true :text (code-message (:code body)) :near "memo-thread-section"})]
      {:state s :fx [[:html (render s)]]})))

(defn memo-loaded [state body]
  (if (:ok body)
    (let [memo (:memo body)
          s (assoc state :memo-selected-row memo :memo-gantt-pick nil)
          cand-fx (when (:can_link_gantt memo)
                    (if (= "admin" (str (:kind state)))
                      [[:api "GET" "/api/admin/gantt/rows" nil :memo-gantt-candidates-loaded]]
                      [[:api "GET" "/api/user/gantt" nil :memo-gantt-candidates-loaded]]))]
      (if (seq cand-fx)
        {:state (assoc s :memo-gantt-candidates [])
         :fx (into [[:html (render (assoc s :memo-gantt-candidates []))]] cand-fx)}
        (guarded (assoc s :memo-gantt-candidates []))))
    (let [s (assoc state :memo-selected nil :memo-selected-row nil :memo-replies []
                   :memo-gantt-candidates []
                   :flash {:error? true :text (code-message (:code body)) :near "memo-timeline"})]
      {:state s :fx [[:html (render s)]]})))

(defn memo-gantt-candidates-loaded [state body]
  (if (:ok body)
    (guarded (assoc state :memo-gantt-candidates (vec (or (:rows body) []))))
    (let [s (assoc state :memo-gantt-candidates []
                   :flash {:error? true :text (code-message (:code body)) :near "memo-thread-section"})]
      {:state s :fx [[:html (render s)]]})))

(defn memo-save-result [state body]
  (let [near (or (:pending-flash-near state) "memo-compose-section")
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [memo (:memo body)
            text (if (memo-draft? memo) (m :memos-draft-saved) (m :memos-posted))
            s (flash-ok-state (assoc state
                                     :memo-compose {}
                                     :memo-last-saved memo)
                              text near)]
        {:state s :fx (memo-refresh-fx s)})
      (let [s (assoc state :flash {:error? true :text (memo-code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-publish-result [state body]
  (let [near (or (:pending-flash-near state) (memo-list-near state))
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [s (flash-ok-state state (m :memos-published) near)]
        {:state s :fx (memo-refresh-fx s)})
      (let [s (assoc state :flash {:error? true :text (memo-code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-delete-result [state body]
  (let [near (or (:pending-flash-near state) (memo-list-near state))
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [s (flash-ok-state (assoc state :memo-selected nil :memo-selected-row nil
                                     :memo-replies [])
                              (m :memos-deleted) near)]
        {:state s :fx (memo-refresh-fx s)})
      (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-bookmark-result [state body]
  (let [near (or (:pending-flash-near state) (memo-list-near state))
        state (dissoc state :pending-flash-near)]
    (if (:ok body)
      (let [text (if (:memo body) (m :memos-bookmarked) (m :memos-unbookmarked))
            s (flash-ok-state state text near)]
        {:state s :fx (memo-refresh-fx s)})
      (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn- replace-memo-in-list [rows memo]
  (let [id (str (:id memo))]
    (mapv (fn [m] (if (= id (str (:id m))) memo m)) (or rows []))))

(defn- upsert-memo-everywhere [state memo]
  (let [id (str (:id memo))
        sel? (and (:memo-selected-row state)
                  (= id (str (:id (:memo-selected-row state)))))]
    (cond-> (-> state
                (update :memos replace-memo-in-list memo)
                (update :memo-replies replace-memo-in-list memo)
                (update :memo-drafts replace-memo-in-list memo)
                (update :memo-bookmarks replace-memo-in-list memo)
                (update :memo-search-results replace-memo-in-list memo)
                (assoc :memo-last-saved memo))
      sel? (assoc :memo-selected-row memo))))

(defn memo-gantt-result [state body]
  (let [near (or (:pending-flash-near state) "memo-thread-section")
        state (dissoc state :pending-flash-near)
        unlink? (boolean (:memo-gantt-unlinking state))
        state (dissoc state :memo-gantt-unlinking)]
    (if (:ok body)
      (let [memo (:memo body)
            text (if unlink? (m :memos-gantt-unlinked) (m :memos-gantt-linked))
            s (-> (upsert-memo-everywhere state memo)
                  (assoc :memo-selected-row memo)
                  (flash-ok-state text near))]
        {:state s :fx (into (or (memo-thread-fx s) [])
                            (when (:can_link_gantt memo)
                              (if (= "admin" (str (:kind s)))
                                [[:api "GET" "/api/admin/gantt/rows" nil :memo-gantt-candidates-loaded]]
                                [[:api "GET" "/api/user/gantt" nil :memo-gantt-candidates-loaded]])))})
      (let [s (assoc state :flash {:error? true :text (memo-code-message (:code body)) :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-attach-result [state body]
  (let [near (or (:pending-flash-near state) (memo-list-near state))
        state (dissoc state :pending-flash-near :memo-upload-status)]
    (if (:ok body)
      (let [s (-> (flash-ok-state state (m :memos-upload-done) near)
                  (cond-> (:memo body) (upsert-memo-everywhere (:memo body)))
                  (assoc :memo-upload-status :done))]
        {:state s :fx [[:html (render s)]]})
      (let [s (assoc state
                     :memo-upload-status :failed
                     :flash {:error? true
                             :text (code-message (:code body))
                             :near near})]
        {:state s :fx [[:html (render s)]]}))))

(defn memo-search-result [state body]
  (if (:ok body)
    (guarded (assoc state
                    :memo-search-results (vec (or (:memos body) []))
                    :memo-search-active? true
                    :flash nil))
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))
                                 :near "memo-search-section"})]
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
    (let [s (assoc state
                   :session {:email (:email body)}
                   :ui-lang (normalize-lang (:ui_lang body))
                   :flash nil)]
      {:state s
       :fx [[:nav (home-after-login s)]]})
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
                   :flash {:error? false :text (m :invite-ok) :near "invite-form-section"})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near "invite-form-section"})]
      {:state s :fx [[:html (render s)]]})))

(defn after-password [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (m :password-ok) :near "password-form-section"})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body)) :near "password-form-section"})]
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
        (flash-html-state state (code-message "title_required") "gantt-titles")
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
        (flash-html-state state (m :gantt-title-select) "gantt-titles")
        (str/blank? name)
        (flash-html-state state (code-message "title_required") "gantt-titles")
        :else
        {:state state
         :fx [[:api "PUT" (str "/api/user/gantt/titles/" id) {:name name}
               :gantt-title-save-result]]}))
    "delete-gantt-title"
    (let [id (str/trim (as-text (if (nil? (:id form))
                                  (:gantt-title-selected state)
                                  (:id form))))]
      (if (str/blank? id)
        (flash-html-state state (m :gantt-title-select) "gantt-titles")
        {:state state
         :fx [[:api "DELETE" (str "/api/user/gantt/titles/" id) nil
               :gantt-title-delete-result]]}))
    "select-gantt-row"
    (let [id (str/trim (as-text (:id form)))
          row (gantt-row-by-id state id)
          s (assoc state :gantt-selected (when-not (str/blank? id) id)
                   :gantt-title-selected (or (:title_id row) (:gantt-title-selected state))
                   :gantt-progress-days nil
                   :gantt-work-times []
                   :gantt-checklist-items []
                   :flash nil)
          child-fx (when (and row (not (str/blank? id)))
                     (gantt-children-load-fx (:id row)))]
      (if (gantt-row-applicable? row)
        {:state (assoc s :gantt-progress nil)
         :fx (into [[:api "GET" (str "/api/user/gantt/" (:id row) "/progress") nil :gantt-progress-loaded]]
                   child-fx)}
        (if (seq child-fx)
          {:state (assoc s :gantt-progress nil) :fx (vec child-fx)}
          (guarded (assoc s :gantt-progress nil)))))
    "add-gantt-row"
    (let [tid (str/trim (as-text (if (nil? (:title_id form))
                                   (:gantt-title-selected state)
                                   (:title_id form))))
          title (str/trim (as-text (:title form)))
          title' (if (str/blank? title) (m :gantt-work-new-placeholder) title)
          start (str/trim (as-text (:start_at form)))
          end (str/trim (as-text (:end_at form)))
          wn-raw (str/trim (as-text (:work_name form)))
          wn (if (str/blank? wn-raw) title' wn-raw)
          near (if (= :gantt (:page state)) "gantt-add-form" "works-add-form")]
      (cond
        (and (= :gantt (:page state)) (str/blank? tid))
        (flash-html-state state (m :gantt-title-select) near)
        (or (str/blank? start) (str/blank? end))
        (flash-html-state state (m :time-invalid) near)
        :else
        {:state state
         :fx [[:api "POST" "/api/user/gantt"
               {:title title'
                :title_id (when-not (str/blank? tid) tid)
                :start_at start
                :end_at end
                :work_name wn
                :field_ids []}
               :gantt-save-result]]}))
    "save-gantt-row"
    (let [id (str/trim (as-text (if (nil? (:id form)) (:gantt-selected state) (:id form))))
          body (gantt-body-from-form form (:gantt-title-selected state))
          fids (:field_ids body)
          wn (str/trim (as-text (:work_name body)))
          version (:version (gantt-row-by-id state id))
          body' (cond-> (assoc body :title_id (let [tid (str/trim (as-text (:title_id body)))]
                                                (when-not (str/blank? tid) tid)))
                  (some? version) (assoc :version version))
          near (if (= :gantt (:page state)) "gantt-save-form" "works-save-form")]
      (cond
        (str/blank? id)
        (flash-html-state state (m :gantt-not-found) near)
        (and (= :gantt (:page state)) (nil? (:title_id body')))
        (flash-html-state state (m :gantt-title-select) near)
        (and (seq fids) (str/blank? wn))
        (flash-html-state state (m :gantt-work-needed) near)
        (or (str/blank? (:start_at body')) (str/blank? (:end_at body')))
        (flash-html-state state (m :time-invalid) near)
        :else
        {:state (assoc state :checklist-left
                       (when (and (= "done" (:execution_status body'))
                                  (same-gantt-id? (:gantt-selected state) id))
                         (count (remove #(= "done" (:status %)) (:gantt-checklist-items state)))))
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
    "set-works-filter"
    (let [range (str/trim (as-text (:range form)))
          allowed (set works-default-statuses)]
      (guarded (assoc state
                      :works-range (if (contains? daily-ranges range) range works-default-range)
                      :works-statuses (vec (filter allowed (form-status-list form)))
                      :flash nil)))
    "set-daily-range"
    (let [range (str/trim (as-text (:range form)))
          range' (if (contains? daily-ranges range) range "today")
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
        (flash-html-state state (m :gantt-not-found) "daily-list")
        (not (#{"not_started" "in_progress" "done"} status))
        (flash-html-state state (m :execution-status-invalid) "daily-list")
        :else
        {:state (assoc state :flash nil
                       :checklist-left (when (= "done" status)
                                         (checklist-left (:checklist_done row) (:checklist_total row))))
         :fx [[:api "PUT" (str "/api/user/gantt/" id "/status")
               {:execution_status status}
               :daily-status-save-result]]}))
    "add-work-time"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          start (str/trim (as-text (:start_at form)))
          end (str/trim (as-text (:end_at form)))
          near (children-near state "-work-time-add-box")]
      (cond
        (str/blank? gid) (flash-html-state state (m :gantt-not-found) near)
        (or (str/blank? start) (str/blank? end)) (flash-html-state state (m :time-invalid) near)
        :else
        {:state (assoc state :pending-flash-near near)
         :fx [[:api "POST" (str "/api/user/gantt/" gid "/work-times")
               {:start_at start :end_at end}
               :work-time-save-result]]}))
    "save-work-time"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          tid (str/trim (as-text (:id form)))
          start (str/trim (as-text (:start_at form)))
          end (str/trim (as-text (:end_at form)))
          near (children-near state "-work-times")]
      (cond
        (or (str/blank? gid) (str/blank? tid)) (flash-html-state state (m :work-time-not-found) near)
        (or (str/blank? start) (str/blank? end)) (flash-html-state state (m :time-invalid) near)
        :else
        {:state (assoc state :pending-flash-near near)
         :fx [[:api "PUT" (str "/api/user/gantt/" gid "/work-times/" tid)
               {:start_at start :end_at end}
               :work-time-save-result]]}))
    "delete-work-time"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          tid (str/trim (as-text (:id form)))
          near (children-near state "-work-times")]
      (if (or (str/blank? gid) (str/blank? tid))
        (flash-html-state state (m :work-time-not-found) near)
        {:state state
         :fx [[:api "DELETE" (str "/api/user/gantt/" gid "/work-times/" tid) nil
               :work-time-delete-result]]}))
    "add-checklist-item"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          label (str/trim (as-text (:label form)))
          near (children-near state "-checklist-add-box")]
      (cond
        (str/blank? gid) (flash-html-state state (m :gantt-not-found) near)
        (str/blank? label) (flash-html-state state (m :label-required) near)
        :else
        {:state (assoc state :pending-flash-near near)
         :fx [[:api "POST" (str "/api/user/gantt/" gid "/checklist-items")
               {:label label}
               :checklist-item-save-result]]}))
    "save-checklist-item"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          cid (str/trim (as-text (:id form)))
          label (str/trim (as-text (:label form)))
          status (str/trim (as-text (:status form)))
          near (children-near state "-checklist")]
      (cond
        (or (str/blank? gid) (str/blank? cid)) (flash-html-state state (m :checklist-item-not-found) near)
        (str/blank? label) (flash-html-state state (m :label-required) near)
        (not (#{"pending" "done"} status)) (flash-html-state state (m :checklist-status-invalid) near)
        :else
        {:state (assoc state :pending-flash-near near)
         :fx [[:api "PUT" (str "/api/user/gantt/" gid "/checklist-items/" cid)
               {:label label :status status}
               :checklist-item-save-result]]}))
    "delete-checklist-item"
    (let [gid (str/trim (as-text (if (nil? (:gantt_id form)) (:gantt-selected state) (:gantt_id form))))
          cid (str/trim (as-text (:id form)))
          near (children-near state "-checklist")]
      (if (or (str/blank? gid) (str/blank? cid))
        (flash-html-state state (m :checklist-item-not-found) near)
        {:state state
         :fx [[:api "DELETE" (str "/api/user/gantt/" gid "/checklist-items/" cid) nil
               :checklist-item-delete-result]]}))
    nil))

(defn- memo-id-of [form]
  (let [v (str/trim (as-text (:id form)))]
    (when-not (str/blank? v) v)))

(defn- memo-status-of [form default]
  (let [st (str/trim (as-text (:status form)))]
    (if (#{"draft" "published"} st) st default)))

(defn- memo-compose-of [form]
  {:body (as-text (:body form))
   :tags (as-text (:tags form))
   :links (as-text (:links form))})

(defn- memo-content-body [form]
  {:body (as-text (:body form))
   :tags (split-lines-csv (:tags form))
   :links (split-lines-csv (:links form))})

(defn- memo-search-path [q adv]
  (str "/api/memos/search?scope=all&q=" (encode-q q)
       (apply str
              (for [[k nm] [[:exclude "exclude"] [:from "from"] [:to "to"] [:author "author"]]
                    :let [v (str/trim (as-text (get adv k)))]
                    :when (not (str/blank? v))]
                (str "&" nm "=" (encode-q v))))))

(defn- submit-memo-new [state form status]
  (let [content (memo-content-body form)
        s (assoc state :memo-compose (memo-compose-of form))]
    (if (= "published" status)
      (if (str/blank? (str/trim (:body content)))
        (flash-html-state s (m :memos-body-required) "memo-compose-section")
        {:state (assoc s :pending-flash-near "memo-compose-section")
         :fx [[:api "POST" "/api/memos" (assoc content :status status) :memo-save-result]]})
      {:state (assoc s :pending-flash-near "memo-compose-section")
       :fx [[:api "POST" "/api/memos" (assoc content :status status) :memo-save-result]]})))

(defn- submit-memo-act [state form act]
  (case act
    "memo-publish-new"
    (submit-memo-new state form (memo-status-of form "published"))

    "memo-draft-new"
    (submit-memo-new state form (memo-status-of form "draft"))

    "memo-reply"
    (let [pid (str/trim (as-text (:parent_id form)))
          status (memo-status-of form "published")
          content (memo-content-body form)]
      (if (str/blank? pid)
        (flash-html-state state (m :memo-not-found) "memo-thread-section")
        (if (= "published" status)
          (if (str/blank? (str/trim (:body content)))
            (flash-html-state state (m :memos-body-required) "memo-thread-section")
            {:state (assoc state :pending-flash-near "memo-thread-section")
             :fx [[:api "POST" "/api/memos"
                   (assoc content :status status :parent_id pid)
                   :memo-save-result]]})
          {:state (assoc state :pending-flash-near "memo-thread-section")
           :fx [[:api "POST" "/api/memos"
                 (assoc content :status status :parent_id pid)
                 :memo-save-result]]})))

    "memo-search"
    (let [q (str/trim (as-text (:q form)))
          adv (select-keys form [:exclude :from :to :author])
          s (assoc state :memo-search-q q :memo-search-form adv
                   :memo-search-active? true :flash nil)]
      {:state s :fx [[:api "GET" (memo-search-path q adv) nil :memo-search-result]]})

    "memo-search-advanced-toggle"
    (guarded (assoc state
                    :memo-search-advanced? (not (boolean (:memo-search-advanced? state)))
                    :flash nil))

    "memo-search-clear"
    (guarded (assoc state
                    :memo-search-active? false
                    :memo-search-results nil
                    :memo-search-q ""
                    :memo-search-form {}
                    :flash nil))

    "memo-select"
    (if-let [id (memo-id-of form)]
      (let [s (assoc state :memo-selected id
                     :memo-selected-row (memo-by-id state id)
                     :memo-replies [] :flash nil)
            fx (memo-thread-fx s)]
        (if (seq fx) {:state s :fx fx} (guarded s)))
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-close-thread"
    (guarded (assoc state :memo-selected nil :memo-selected-row nil :memo-replies []
                    :memo-gantt-candidates [] :memo-gantt-pick nil :flash nil))

    "memo-gantt-link"
    (let [id (memo-id-of form)
          gid (str/trim (as-text (:gantt_id form)))]
      (cond
        (str/blank? (str id))
        (flash-html-state state (m :memo-not-found) "memo-thread-section")
        (str/blank? gid)
        (flash-html-state state (m :gantt-id-required) "memo-thread-section")
        :else
        {:state (assoc state :pending-flash-near "memo-thread-section"
                       :memo-gantt-pick gid
                       :memo-gantt-unlinking false)
         :fx [[:api "PUT" (str "/api/memos/" id "/gantt") {:gantt_id gid}
               :memo-gantt-result]]}))

    "memo-gantt-unlink"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near "memo-thread-section"
                     :memo-gantt-unlinking true)
       :fx [[:api "DELETE" (str "/api/memos/" id "/gantt") nil :memo-gantt-result]]}
      (flash-html-state state (m :memo-not-found) "memo-thread-section"))

    "memo-delete"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near (memo-list-near state))
       :fx [[:api "DELETE" (str "/api/memos/" id) nil :memo-delete-result]]}
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-publish-draft"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near (memo-list-near state))
       :fx [[:api "POST" (str "/api/memos/" id "/publish") {} :memo-publish-result]]}
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-update-draft"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near (memo-list-near state))
       :fx [[:api "PUT" (str "/api/memos/" id) (memo-content-body form) :memo-save-result]]}
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-update"
    (if-let [id (memo-id-of form)]
      (let [near (if (nil? (:memo-selected state))
                   "memo-timeline"
                   "memo-thread-section")]
        {:state (assoc state :pending-flash-near near)
         :fx [[:api "PUT" (str "/api/memos/" id) (memo-content-body form) :memo-save-result]]})
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-bookmark"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near (memo-list-near state))
       :fx [[:api "POST" (str "/api/memos/" id "/bookmark") {} :memo-bookmark-result]]}
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-unbookmark"
    (if-let [id (memo-id-of form)]
      {:state (assoc state :pending-flash-near (memo-list-near state))
       :fx [[:api "DELETE" (str "/api/memos/" id "/bookmark") nil :memo-bookmark-result]]}
      (flash-html-state state (m :bookmark-not-found) (memo-list-near state)))

    "memo-attach"
    (if-let [id (memo-id-of form)]
      (let [near (if (not= :memos (:page state))
                   (memo-list-near state)
                   (if (nil? (:memo-selected state))
                     "memo-compose-section"
                     "memo-thread-section"))]
        {:state (assoc state :pending-flash-near near :memo-upload-status :uploading)
         :fx [[:upload-status (m :memos-uploading)]
              [:upload "POST" (str "/api/memos/" id "/attachments")
               (dissoc form :id) :memo-attach-result]]})
      (flash-html-state state (m :memo-not-found) (memo-list-near state)))

    "memo-detach"
    (let [id (memo-id-of form)
          aid (str/trim (as-text (:attachment_id form)))]
      (if (str/blank? (str id))
        (flash-html-state state (m :memo-not-found) (memo-list-near state))
        (if (str/blank? aid)
          (flash-html-state state (m :memo-not-found) (memo-list-near state))
          {:state (assoc state :pending-flash-near (memo-list-near state))
           :fx [[:api "DELETE" (str "/api/memos/" id "/attachments/" aid) nil :memo-attach-result]]})))

    "memo-more"
    (let [bid (str/trim (as-text (:before_id form)))]
      (if (str/blank? bid)
        (guarded state)
        {:state (assoc state :memo-before-id bid :flash nil)
         :fx [[:api "GET" (str "/api/memos?before_id=" (encode-q bid)) nil :memos-loaded]]}))

    nil))

(defn- submit-auth-act
  "入場・招待・パスワードの操作。利用者と管理者で経路だけ違う。"
  [state form act kind]
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
    nil))

(defn- submit-place-act
  "所在地・下地地図・農地ナビ取り込みと地図の操作の切り替え。"
  [state form act]
  (case act
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
    "save-image-extent" {:state state :fx [[:api "PUT" "/api/user/place/image" form :image-save-result]]}
    "upload-basemap" {:state state :fx [[:upload "PUT" (str "/api/user/basemaps/" (:kind form)) form :basemap-upload-result]]}
    nil))

(defn- submit-field-act
  "ほ場の作成・更新・削除・分割・結合・取り込み。"
  [state form act]
  (case act
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
    "import-fields" {:state state :fx [[:upload "POST" "/api/user/fields/import" form :field-save-result]]}
    nil))

(defn- submit-paint-act
  "作業名の選択と塗りの確定・削除。"
  [state form act]
  (case act
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
    nil))

(defn- submit-order-act
  "作業依頼・作業日誌・他人の塗り・関係の切断。"
  [state form act]
  (case act
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
      :work-times-loaded (work-times-loaded state arg)
      :checklist-items-loaded (checklist-items-loaded state arg)
      :work-time-save-result (work-time-save-result state arg)
      :work-time-delete-result (work-time-delete-result state arg)
      :checklist-item-save-result (checklist-item-save-result state arg)
      :checklist-item-delete-result (checklist-item-delete-result state arg)
      :orders-loaded (orders-loaded state arg)
      :order-loaded (order-loaded state arg)
      :order-map-loaded (order-map-loaded state arg)
      :order-save-result (order-save-result state arg)
      :journal-save-result (journal-save-result state arg)
      :others-fields-loaded (others-fields-loaded state arg)
      :others-work-names-loaded (others-work-names-loaded state arg)
      :others-paints-loaded (others-paints-loaded state arg)
      :relation-cut-result (relation-cut-result state arg)
      :memos-loaded (memos-loaded state arg)
      :memo-drafts-loaded (memo-drafts-loaded state arg)
      :memo-bookmarks-loaded (memo-bookmarks-loaded state arg)
      :memo-replies-loaded (memo-replies-loaded state arg)
      :memo-loaded (memo-loaded state arg)
      :memo-gantt-candidates-loaded (memo-gantt-candidates-loaded state arg)
      :memo-gantt-result (memo-gantt-result state arg)
      :memo-save-result (memo-save-result state arg)
      :memo-publish-result (memo-publish-result state arg)
      :memo-delete-result (memo-delete-result state arg)
      :memo-bookmark-result (memo-bookmark-result state arg)
      :memo-attach-result (memo-attach-result state arg)
      :memo-search-result (memo-search-result state arg)
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
                       :gantt-work-times [] :gantt-checklist-items []
                       :gantt-axis "day" :gantt-orient "time-h" :form {} :paint-data nil)

                (= :works (:page s))
                (assoc s :gantt-selected (works-query-id (:search s))
                       :gantt-work-times [] :gantt-checklist-items []
                       :gantt-progress nil :gantt-progress-days nil
                       :works-range works-default-range
                       :works-statuses works-default-statuses)

                (= :daily (:page s))
                (assoc s :daily-range daily-default-range
                       :daily-statuses ["not_started" "in_progress"]
                       :daily-rows [] :daily-total nil :flash nil)

                (= :home (:page s))
                (assoc s :memos [] :memo-selected nil :memo-selected-row nil :memo-replies []
                       :memo-before-id nil :flash nil)

                (= :gantt-progress (:page s))
                (assoc s :gantt-finalize-result nil :form {})

                (#{:orders :orders-new :order :others} (:page s))
                (assoc s :order nil :order-map nil :others-paint-data nil
                       :form {} :orders-sent [] :orders-received [] :others-fields [])

                (memo-page? (:page s))
                (assoc s :memos [] :memo-selected nil :memo-selected-row nil :memo-replies []
                       :memo-drafts [] :memo-bookmarks [] :memo-compose {}
                       :memo-search-q "" :memo-search-form {} :memo-search-advanced? false
                       :memo-search-results nil :memo-search-active? false
                       :memo-last-saved nil
                       :memo-before-id nil :memo-admin-tried? nil :form {})

                :else s)]
        (if (and (:session s) (= (:kind s) (:kind state)))
          (session-loaded s {:ok true :email (get-in s [:session :email])})
          {:state (assoc s :session nil)
           :fx [[:restore-guest-lang (:kind s)]
                [:session (:kind s)]]}))
      :submit
      (let [act (:act arg)
            form (:form arg)]
        (or (submit-auth-act state form act (:kind state))
            (submit-place-act state form act)
            (submit-field-act state form act)
            (submit-paint-act state form act)
            (submit-gantt-act state form act)
            (submit-memo-act state form act)
            (submit-order-act state form act)
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
