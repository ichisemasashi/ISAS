(ns isas.p6-coverage-test
  "工程6の分岐を cloverage 用に踏む。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.mail :as mail]
            [isas.test-util :as tu]
            [isas.ui :as ui]
            [ring.mock.request :as mock]))

(deftest p6-coverage-branches
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "p6c@example.com")
            usid (tu/user-sid app "p6c@example.com" pw)
            admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
        (testing "mail normalize and send with lang"
          (is (= "ja" (mail/normalize-lang nil)))
          (is (= "en" (mail/normalize-lang "en")))
          (is (string? (mail/reset-body "admin" "http://x/" "t" "en")))
          (is (true? (mail/send-reset! (:conf sys) {:kind "user" :to "a@b.c" :token "t" :ui-lang "en"}))))
        (testing "accounts language edge"
          (is (= "ja" (accounts/account-ui-lang nil)))
          (is (= "ja" (accounts/account-ui-lang {:ui_lang ""})))
          (is (= "ja" (accounts/account-ui-lang {:ui_lang "fr"})))
          (is (true? (:ok (accounts/set-language sys "admin" admin "ja"))))
          (is (= "lang_invalid" (:code (accounts/set-language sys "admin" admin nil)))))
        (testing "http language put malformed"
          (is (= "lang_invalid"
                 (:code (tu/parse (app (tu/as-user (-> (mock/request :put "/api/user/language")
                                                      (mock/content-type "application/json")
                                                      (mock/body "{"))
                                                    "user" usid))))))
          (is (= "unauthorized"
                 (:code (tu/parse (tu/put-json app "/api/admin/language" {:ui_lang "en"}))))))
        (testing "reset request with blank ui_lang"
          (is (true? (:ok (accounts/request-reset sys "user" "p6c@example.com" ""))))
          (is (true? (:ok (accounts/request-reset sys "user" "nobody@example.com" "en")))))
        (testing "date format edge branches"
          (is (= "2026年9月12日"
                 (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-datetime "2026-09-12"))))
          (is (= ""
                 (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-datetime "bad" "xx"))))
          (is (nil? (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-date "nope"))))
          (is (nil? (ui/with-ui-lang {:ui-lang "ja"} #(ui/format-display-time "xx"))))
          (let [h (ui/render (assoc (ui/init-state)
                                    :page :orders :session {:email "a"}
                                    :orders-sent [{:id 1 :work_date "not-a-date" :work_name "田植え" :status "open"}]
                                    :orders-received []))]
            (is (re-find #"not-a-date" h)))
          (let [h (ui/render (assoc (ui/init-state)
                                    :page :order :session {:email "a"}
                                    :order {:id 1 :role "recipient" :status "open"
                                            :work_date "bad" :start_time "xx" :end_time "yy"
                                            :work_name "田植え" :body ""
                                            :recipient_emails [] :fields [] :journals []}))]
            (is (re-find #"bad" h))
            (is (re-find #"xx" h))))
        (testing "boot / set-lang / language-saved edges"
          (let [r (ui/handle (dissoc (ui/init-state) :ui-lang)
                             [:boot {:path "/" :search "" :narrow? false}])]
            (is (= "ja" (get-in r [:state :ui-lang]))))
          (let [r (ui/handle (assoc (ui/init-state) :ui-lang "en")
                             [:boot {:path "/" :search "" :narrow? false}])]
            (is (= "en" (get-in r [:state :ui-lang]))))
          (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :ui-lang "en" :page :home)
                             [:language-saved {:ok false}])]
            (is (true? (get-in r [:state :flash :error?])))
            (is (re-find #"Invalid language" (get-in r [:state :flash :text]))))
          (let [r (ui/handle (assoc (ui/init-state) :session {:email "admin@example.com"}
                                    :kind "admin" :ui-lang "ja" :page :home)
                             [:set-lang {:lang "en"}])]
            (is (= "/api/admin/language" (nth (first (:fx r)) 2))))
          (is (= (:lang-invalid ui/messages)
                 (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "lang_invalid"))))
          (is (re-find #"Invalid language"
                       (ui/with-ui-lang {:ui-lang "en"} #(ui/code-message "lang_invalid")))))
        (testing "boot with guest lang and session ui_lang"
          (let [r (ui/handle (ui/init-state) [:boot {:path "/" :search "" :narrow? false :ui-lang "en"}])]
            (is (= "en" (get-in r [:state :ui-lang])))
            (is (= :session (ffirst (:fx r)))))
          (let [r (ui/handle (assoc (ui/init-state) :ui-lang "en" :page :home :kind "user")
                             [:session-loaded {:ok true :email "a@example.com" :ui_lang "ja"}])]
            (is (= "ja" (get-in r [:state :ui-lang]))))
          (let [r (ui/handle (assoc (ui/init-state) :ui-lang "en" :page :home)
                             [:session-loaded {:ok true :email "a@example.com"}])]
            (is (= "en" (get-in r [:state :ui-lang]))))
          (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :ui-lang "en" :kind "user")
                             [:logout-result {:ok true}])]
            (is (= :restore-guest-lang (ffirst (:fx r)))))
          (is (re-find #"Request password reset"
                       (ui/render (assoc (ui/init-state) :page :reset-request :session nil :ui-lang "en"))))
          (is (re-find #"㎡" (ui/render (assoc (ui/init-state) :page :fields :session {:email "a"}
                                               :fields [{:id 1 :name "n" :area_ha 1 :area_m2 2}])))))))))
