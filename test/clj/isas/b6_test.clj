(ns isas.b6-test
  "基本試験仕様書_工程6 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.mail :as mail]
            [isas.orders :as orders]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (let [base {:kind "user" :session {:email "a@example.com"} :ui-lang "ja"}
        opts (if (and (= :gantt (:page opts))
                      (seq (:fields opts))
                      (empty? (:gantt-titles opts)))
               (assoc opts
                      :gantt-titles [{:id 10 :name "題A"}]
                      :gantt-title-selected (or (:gantt-title-selected opts) 10))
               opts)
        opts (if (and (= :gantt (:page opts)) (seq (:gantt-rows opts)))
               (update opts :gantt-rows
                       (fn [rows]
                         (mapv (fn [r]
                                 (if (contains? r :title_id) r (assoc r :title_id 10)))
                               rows)))
               opts)]
    (tu/page-html (merge base opts))))

(deftest b6-blocks
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b6a@example.com")
            usid (tu/user-sid app "b6a@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "b6a@example.com"))
            pw2 (tu/invite-pw app asid "b6b@example.com")
            usid2 (tu/user-sid app "b6b@example.com" pw2)
            uid2 (:id (db/find-user-by-email (:ds sys) "b6b@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])]
        (testing "B6-2-01 / B6-4.8 / B6-5 切替が出る"
          (is (re-find #"data-select=\"lang\"" (html {:page :login :session nil})))
          (is (re-find #"data-select=\"lang\"" (html {:page :home :kind "admin"})))
          (is (re-find #"data-select=\"lang\"" (html {:page :orders :narrow? true})))
          (is (re-find #"User login" (html {:page :login :session nil :ui-lang "en"}))))
        (testing "B6-2-03 / B6-3.4 対象外の置き場所は無い"
          (is (not (re-find #"農機|GAP|在庫|オフライン下書き|Excel|公開登録"
                            (html {:page :home}))))
          (is (= :unknown (:page (ui/route-for "/signup")))))
        (testing "B6-4.8-02 訳さない"
          (let [h (html {:page :order :ui-lang "en"
                         :order {:id 1 :role "issuer" :status "open"
                                 :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                 :work_name "田植え" :body "本文そのまま"
                                 :recipient_emails ["b6b@example.com"]
                                 :fields [{:id fid :name "北" :visible true}] :journals []}
                         :order-map {:work_name "田植え"
                                     :fields [{:id fid :name "北" :status "none"
                                               :geojson tu/square}]}})]
            (is (re-find #"田植え" h))
            (is (re-find #"本文そのまま" h))
            (is (re-find #"北" h))))
        (testing "B6-4.8-03 既定は日本語"
          (is (= "ja" (:ui-lang (ui/init-state))))
          (is (= "ja" (ui/normalize-lang nil)))
          (is (= "ja" (ui/normalize-lang "fr"))))
        (testing "B6-4.8-05 / B6-6 / B6-7.6 ログイン後はアカウントに残る"
          (is (true? (:ok (tu/parse (tu/put-json app "/api/user/language"
                                                 {:ui_lang "en"} "user" usid)))))
          (is (= "en" (:ui_lang (tu/parse (tu/get-path app "/api/user/session" "user" usid)))))
          (let [login (accounts/login sys "user" "b6a@example.com" pw)]
            (is (= "en" (:ui_lang login)))))
        (testing "B6-5.2-01 管理者も残る"
          (is (true? (:ok (tu/parse (tu/put-json app "/api/admin/language"
                                                 {:ui_lang "en"} "admin" asid)))))
          (is (= "en" (:ui_lang (tu/parse (tu/get-path app "/api/admin/session" "admin" asid))))))
        (testing "B6-7.6-03 切替しても作業名・塗り％は数値のまま"
          (paints/create-paint sys uid {:field_id fid :work_name "田植え" :geojson tu/square-inner})
          (let [tid (:id (:title (gantt/create-title sys uid {:name "題"})))
                row (gantt/create-row sys uid {:title_id tid :title "g" :start_at "2026-09-12T08:00"
                                               :end_at "2026-09-12T17:00" :work_name "田植え"
                                               :field_ids [fid]})
                gid (get-in row [:row :id])
                prog (gantt/row-progress sys uid gid)
                h (html {:page :gantt :ui-lang "en" :fields [{:id fid :name "北" :geojson tu/square}]
                         :gantt-rows [(:row row)] :gantt-selected gid
                         :gantt-progress prog})]
            (is (true? (:ok prog)))
            (is (re-find #"田植え" h))
            (is (re-find (re-pattern (str "data-percent=\"" (:percent prog) "\"")) h))
            (is (re-find #"data-percent-unit=\"%\"" h))))
        (testing "B6-7.1〜7.5 受入の要所（実データ）"
          (is (true? (:ok (orders/create-order sys uid {:work_date "2026-09-12"
                                                        :start_time "08:00" :end_time "17:00"
                                                        :work_name "田植え" :body ""
                                                        :recipient_emails ["b6b@example.com"]
                                                        :field_ids [fid]}))))
          (let [oid (:id (first (:sent (orders/list-orders sys uid))))]
            (is (true? (:ok (orders/post-journal sys uid2 oid {:body "日誌"}))))
            (is (true? (:ok (orders/close-order sys uid oid)))))
          (is (true? (:ok (orders/cut-relation sys "b6a@example.com" "b6b@example.com"))))
          (is (= 0 (count (:fields (orders/others-fields sys uid2))))))
        (testing "B6-8 / B6-9"
          (is (= "ISAS password reset" (mail/reset-subject "user" "en")))
          (is (nil? (http/match-api :get "/api/oauth")))
          (is (nil? (http/match-api :put "/api/user/locale"))))
        (testing "B6-10-01 言語 API がある"
          (is (some? (http/match-api :put "/api/user/language")))
          (is (some? (http/match-api :put "/api/admin/language"))))))))

(deftest b6-spec-ids-present
  (let [doc (slurp (io/file "docs/基本試験仕様書_工程6.md"))
        ids ["B6-2-01" "B6-2-02" "B6-2-03"
             "B6-4.8-01" "B6-4.8-02" "B6-4.8-03" "B6-4.8-04" "B6-4.8-05" "B6-4.8-06" "B6-4.8-07"
             "B6-5.1-01" "B6-5.1-02" "B6-5.1-03"
             "B6-5.2-01" "B6-5.3-01" "B6-5.4-01"
             "B6-6-01" "B6-6-02" "B6-6-03"
             "B6-7.6-01" "B6-7.6-02" "B6-7.6-03"
             "B6-7.1-01" "B6-7.2-01" "B6-7.3-01" "B6-7.4-01" "B6-7.5-01"
             "B6-8-01" "B6-8-02" "B6-8-03" "B6-8-04" "B6-8-05"
             "B6-9-01" "B6-9-02" "B6-9-03" "B6-9-04" "B6-9-05"
             "B6-10-01" "B6-10-02"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
