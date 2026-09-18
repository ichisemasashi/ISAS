(ns isas.p6-test
  "詳細試験仕様書_工程6 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.http :as http]
            [isas.mail :as mail]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"} :ui-lang "ja"} opts)))

(deftest p6-screens-and-switcher
  (testing "P6-2.1-01 / P6-5-01〜04 利用者入口の切替"
    (let [ja (html {:page :login :session nil :ui-lang "ja"})
          en (html {:page :login :session nil :ui-lang "en"})]
      (is (re-find #"data-lang=\"ja\"" ja))
      (is (re-find #"data-lang=\"en\"" ja))
      (is (re-find #"日本語" ja))
      (is (re-find #"English" ja))
      (is (re-find #"aria-current=\"true\"" ja))
      (is (re-find #"User login" en))
      (is (re-find #"利用者ログイン" ja))))
  (testing "P6-2.1-02 管理者入口"
    (is (re-find #"data-lang" (html {:page :login :kind "admin" :session nil})))
    (is (re-find #"Admin login"
                 (html {:page :login :kind "admin" :session nil :ui-lang "en"}))))
  (testing "P6-2.1-03 / P6-2.1-04 ログイン後"
    (doseq [page [:home :fields :map :gantt :orders :others :invite :password]]
      (is (re-find #"data-lang" (html {:page page})) (str page)))
    (doseq [page [:home :invite :users :relations :password]]
      (is (re-find #"data-lang" (html {:page page :kind "admin"})) (str "admin-" page))))
  (testing "P6-2.1-05 狭い画面の指示"
    (is (re-find #"data-lang" (html {:page :orders :narrow? true})))
    (is (re-find #"Orders|指示"
                 (html {:page :orders :narrow? true :ui-lang "en"
                        :orders-sent [] :orders-received []}))))
  (testing "P6-2.1-06 html lang は状態の言語（ブラウザが documentElement に載せる）"
    (is (= "en" (ui/ui-lang {:ui-lang "en"})))
    (is (= "ja" (ui/ui-lang {:ui-lang nil}))))
  (testing "P6-2.1-07 言語専用経路は置かない"
    (is (= :unknown (:page (ui/route-for "/en"))))
    (is (= :unknown (:page (ui/route-for "/ja"))))
    (is (nil? (http/match-api :get "/api/user/locale")))))

(deftest p6-api-language
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "p6a@example.com")
            usid (tu/user-sid app "p6a@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "p6a@example.com"))]
        (testing "P6-2.2-01 / P6-4-01 session に ui_lang"
          (let [body (tu/parse (tu/get-path app "/api/user/session" "user" usid))]
            (is (true? (:ok body)))
            (is (= "ja" (:ui_lang body)))))
        (testing "P6-2.2-02 未ログイン"
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/session"))))))
        (testing "P6-2.2-03 / P6-4-02 管理者 session"
          (let [body (tu/parse (tu/get-path app "/api/admin/session" "admin" asid))]
            (is (= "ja" (:ui_lang body)))))
        (testing "P6-2.2-04 PUT user language"
          (is (true? (:ok (tu/parse (tu/put-json app "/api/user/language"
                                                 {:ui_lang "en"} "user" usid)))))
          (is (= "en" (:ui_lang (tu/parse (tu/get-path app "/api/user/session" "user" usid)))))
          (is (= "en" (:ui_lang (db/find-user-by-id (:ds sys) uid)))))
        (testing "P6-2.2-05 PUT admin language"
          (is (true? (:ok (tu/parse (tu/put-json app "/api/admin/language"
                                                 {:ui_lang "en"} "admin" asid)))))
          (is (= "en" (:ui_lang (tu/parse (tu/get-path app "/api/admin/session" "admin" asid))))))
        (testing "P6-2.2-c01 lang_invalid"
          (is (= "lang_invalid"
                 (:code (tu/parse (tu/put-json app "/api/user/language"
                                               {:ui_lang "fr"} "user" usid)))))
          (is (= 401 (:status (tu/put-json app "/api/user/language" {:ui_lang "en"})))))
        (testing "P6-2.2-06 / P6-3.3 再設定メール言語"
          (tu/post-json app "/api/user/password/reset/request"
                        {:email "p6a@example.com" :ui_lang "en"})
          (let [msg (last @(:sent sys))]
            (is (= "ISAS password reset" (:subject msg)))
            (is (re-find #"24 hours" (:body msg))))
          (tu/post-json app "/api/user/password/reset/request"
                        {:email "p6a@example.com" :ui_lang "xx"})
          (is (= "ISAS パスワードの再設定" (:subject (last @(:sent sys)))))
          (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password/reset/request"
                                                  {:email "missing@example.com" :ui_lang "en"}))))))
        (testing "P6-2.2-07 管理者再設定英語"
          (tu/post-json app "/api/admin/password/reset/request"
                        {:email "admin@example.com" :ui_lang "en"})
          (is (= "ISAS admin password reset" (:subject (last @(:sent sys))))))
        (testing "P6-4-03 新しい言語表は持たない"
          (is (not (contains? (tu/table-names (:ds sys)) "languages")))
          (is (contains? (db/table-columns (:ds sys) "users") "ui_lang"))
          (is (contains? (db/table-columns (:ds sys) "admins") "ui_lang")))))))

(deftest p6-ui-handlers-and-flow
  (testing "P6-2.2-08 / P6-2.3 / P6-2.4 画面遷移"
    (let [guest (assoc (ui/init-state) :page :login :kind "user" :session nil :ui-lang "ja")
          to-en (ui/handle guest [:set-lang "en"])]
      (is (= "en" (get-in to-en [:state :ui-lang])))
      (is (= :guest-lang (ffirst (:fx to-en))))
      (is (= :html (first (second (:fx to-en)))))
      (is (re-find #"User login" (second (second (:fx to-en))))))
    (let [s0 (assoc (ui/init-state) :session {:email "a@example.com"} :kind "user" :ui-lang "ja" :page :home)
          r (ui/handle s0 [:set-lang "en"])]
      (is (= "en" (get-in r [:state :ui-lang])))
      (is (= ["PUT" "/api/user/language" {:ui_lang "en"} :language-saved]
             (subvec (first (:fx r)) 1))))
    (let [s0 (assoc (ui/init-state) :session {:email "a@example.com"} :kind "user" :ui-lang "en" :page :home)
          ok (ui/handle s0 [:language-saved {:ok true :ui_lang "en"}])
          bad (ui/handle s0 [:language-saved {:ok false :code "lang_invalid"}])]
      (is (= :html (ffirst (:fx ok))))
      (is (true? (get-in bad [:state :flash :error?]))))
    (testing "P6-2.3-04 ログイン直後はアカウント言語"
      (let [r (ui/handle (assoc (ui/init-state) :ui-lang "en" :kind "user")
                         [:login-result {:ok true :email "a@example.com" :ui_lang "ja"}])]
        (is (= "ja" (get-in r [:state :ui-lang])))
        (is (= :nav (ffirst (:fx r))))))
    (testing "P6-2.4-03 ログアウトはゲスト規則へ（アカウント言語を入口へ書き戻さない）"
      (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user" :ui-lang "en")
                         [:logout-result {:ok true}])]
        (is (nil? (get-in r [:state :session])))
        (is (= :restore-guest-lang (ffirst (:fx r)))))))
  (testing "P6-2.3-01 session 未ログイン時ゲスト言語を返さない"
    (let [r (ui/handle (assoc (ui/init-state) :ui-lang "en" :page :login)
                       [:session-loaded {:ok false :code "unauthorized"}])]
      (is (nil? (get-in r [:state :session])))
      (is (= "en" (get-in r [:state :ui-lang])))))
  (testing "P6-3.1 訳す／訳さない・日付"
    (is (= "2026年9月12日 8:00"
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-datetime "2026-09-12" "08:00"))))
    (is (= "12 Sep 2026 08:00"
           (ui/with-ui-lang {:ui-lang "en"} #(ui/format-display-datetime "2026-09-12" "08:00"))))
    (is (= "2026年9月12日"
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-datetime "2026-09-12" nil))))
    (is (= "12 Sep 2026"
           (ui/with-ui-lang {:ui-lang "en"} #(ui/format-display-date "2026-09-12"))))
    (let [h (html {:page :order :ui-lang "en"
                   :order {:id 1 :role "recipient" :status "open"
                           :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                           :work_name "田植え" :body "頼む"
                           :recipient_emails ["a@example.com"]
                           :fields [{:id 1 :name "北" :visible true}] :journals []}
                   :order-map {:work_name "田植え"
                               :fields [{:id 1 :name "北" :status "partial"
                                         :geojson {:type "Polygon" :coordinates [[[1 2] [3 2] [3 4] [1 4] [1 2]]]}}]}})]
      (is (re-find #"田植え" h))
      (is (re-find #"頼む" h))
      (is (re-find #"北" h))
      (is (re-find #"12 Sep 2026" h))
      (is (re-find #"m²|%|Orders|Journal" h))))
  (testing "P6-3.1-01 単位"
    (is (= "％" (:gantt-percent-unit ui/messages)))
    (is (= "%" (:gantt-percent-unit ui/messages-en)))
    (is (= "㎡" (:unit-m2 ui/messages)))
    (is (= "m²" (:unit-m2 ui/messages-en)))
    (is (= "ha" (:unit-ha ui/messages)))
    (is (= "ha" (:unit-ha ui/messages-en))))
  (testing "P6-5 辞書のキー対応"
    (is (= (set (keys ui/messages)) (set (keys ui/messages-en))))
    (is (empty? (set/difference (set (keys ui/messages)) (set (keys ui/messages-en))))))
  (testing "P6-3.4 / P6-6 対象外"
    (is (= :unknown (:page (ui/route-for "/rtl"))))
    (is (nil? (http/match-api :put "/api/user/language/fr")))
    (is (not (re-find #"農機|GAP|在庫|オフライン|Excel|公開登録"
                      (html {:page :home}))))))

(deftest p6-mail-subjects
  (testing "P6-3.3-01〜04"
    (is (= "ISAS パスワードの再設定" (mail/reset-subject "user" "ja")))
    (is (= "ISAS password reset" (mail/reset-subject "user" "en")))
    (is (= "ISAS 管理者パスワードの再設定" (mail/reset-subject "admin" "ja")))
    (is (= "ISAS admin password reset" (mail/reset-subject "admin" "en"))))
  (testing "P6-3.3-05 英語本文の事実"
    (let [b (mail/reset-body "user" "http://localhost:8080/" "tok" "en")]
      (is (re-find #"/reset\?token=tok" b))
      (is (re-find #"24 hours" b))
      (is (re-find #"ignore" b)))))

(deftest p6-accounts-set-language
  (tu/with-sys
    (fn [sys]
      (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")
            pw (:initial_password (accounts/invite sys "admin" (:id admin) "p6b@example.com"))
            user (db/find-user-by-email (:ds sys) "p6b@example.com")]
        (is (= "ja" (accounts/account-ui-lang user)))
        (is (true? (:ok (accounts/set-language sys "user" user "en"))))
        (is (= "en" (accounts/account-ui-lang (db/find-user-by-id (:ds sys) (:id user)))))
        (is (= "lang_invalid" (:code (accounts/set-language sys "user" user "de"))))
        (let [login (accounts/login sys "user" "p6b@example.com" pw)]
          (is (true? (:ok login)))
          (is (= "en" (:ui_lang login))))))))

(deftest p6-spec-ids-present
  (let [doc (slurp (io/file "docs/詳細試験仕様書_工程6.md"))
        ids ["P6-2.1-01" "P6-2.1-02" "P6-2.1-03" "P6-2.1-04" "P6-2.1-05" "P6-2.1-06" "P6-2.1-07"
             "P6-2.2-01" "P6-2.2-02" "P6-2.2-03" "P6-2.2-04" "P6-2.2-05" "P6-2.2-06" "P6-2.2-07" "P6-2.2-08"
             "P6-2.2-c01"
             "P6-2.3-01" "P6-2.3-02" "P6-2.3-03" "P6-2.3-04"
             "P6-2.4-01" "P6-2.4-02" "P6-2.4-03"
             "P6-3.1-01" "P6-3.1-02" "P6-3.1-03" "P6-3.1-04" "P6-3.1-05"
             "P6-3.2-01"
             "P6-3.3-01" "P6-3.3-02" "P6-3.3-03" "P6-3.3-04" "P6-3.3-05" "P6-3.3-06"
             "P6-3.4-01"
             "P6-4-01" "P6-4-02" "P6-4-03" "P6-4-04"
             "P6-5-01" "P6-5-02" "P6-5-03" "P6-5-04" "P6-5-05"
             "P6-6-01" "P6-6-02" "P6-6-03" "P6-6-04" "P6-6-05" "P6-6-06"
             "P6-7-01" "P6-7-02" "P6-7-03" "P6-7-04" "P6-7-05" "P6-7-06" "P6-7-07" "P6-7-08"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
