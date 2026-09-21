(ns isas.p4-test
  "詳細試験仕様書_工程4 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [ring.mock.request :as mock])
  (:import [java.time Instant]))

(defn- html [opts]
  (let [base {:kind "user" :session {:email "a@example.com"}}
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

(defn- place []
  {:west 139.0 :south 35.0 :east 141.0 :north 37.0})

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "p4@example.com")
        usid (tu/user-sid app "p4@example.com" pw)
        pw2 (tu/invite-pw app asid "p4-other@example.com")
        usid2 (tu/user-sid app "p4-other@example.com" pw2)
        uid (:id (db/find-user-by-email (:ds sys) "p4@example.com"))
        uid2 (:id (db/find-user-by-email (:ds sys) "p4-other@example.com"))]
    {:app app :asid asid :usid usid :usid2 usid2 :uid uid :uid2 uid2}))

(defn- add-field [app usid name gj]
  (:field (tu/parse (tu/post-json app "/api/user/fields" {:name name :geojson gj} "user" usid))))

(defn- row-body
  ([tid title start end] (row-body tid title start end nil []))
  ([tid title start end wn fids]
   (cond-> {:title_id tid :title title :start_at start :end_at end :field_ids fids}
     (some? wn) (assoc :work_name wn))))

(defn- make-title [app usid name]
  (:title (tu/parse (tu/post-json app "/api/user/gantt/titles" {:name name} "user" usid))))

(deftest p4-screens-and-routes
  (testing "P4-2.1-01 / P4-2.2-01 / P4-2.2-03 / P4-5-01〜03 / P4-5-08 / P4-5-09 広い画面のガント"
    (let [h (html {:page :gantt
                   :place (place)
                   :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 10 :name "題A"}]
                   :gantt-title-selected 10
                   :gantt-rows [{:id 1 :title_id 10 :title "予定A" :start_at "2026-09-18T08:00"
                                 :end_at "2026-09-18T17:00" :work_name "田植え" :field_ids [1]}]
                   :gantt-selected 1
                   :gantt-progress {:ok true :applicable true :percent 40
                                    :fields [{:id 1 :status "partial"}]}
                   :work-names ["田植え"]})]
      (is (re-find #">ガント<" h))
      (is (re-find #"gantt-circle" h))
      (is (re-find #"data-percent=\"40\"" h))
      (is (re-find #"gantt-ticks" h))
      (is (re-find #"gantt-save-btn|gantt-add-btn" h))
      (is (= "％" (:gantt-percent-unit ui/messages)))
      (is (re-find #"ol-map" h))
      (is (re-find #"data-gantt-mode=\"1\"" h))
      (is (re-find #"data-target-ids=\"1\"" h))
      (is (re-find #"題名" h))
      (is (re-find #"開始" h))
      (is (re-find #"終了" h))
      (is (re-find #"作業名" h))
      (is (re-find #"対象圃場" h))
      (is (re-find #"関連する題名" h))
      (is (re-find #"id=\"gantt-row-title-id\"" h))
      (is (re-find #"作業を足す" h))
      (is (re-find #"id=\"gantt-delete-btn\"" h))
      (is (re-find #"id=\"gantt-review-btn\"" h))
      (is (not (re-find #"ブラシ|全面完了|分割|合筆|取込" h)))
      (is (= "未" (:status-none ui/messages)))
      (is (= "一部" (:status-partial ui/messages)))
      (is (= "済" (:status-done ui/messages)))))
  (testing "P4-2.1-02 未ログインは / へ"
    (let [fx (:fx (ui/handle (ui/init-state) [:path {:path "/gantt" :search ""}]))]
      (is (= :restore-guest-lang (ffirst fx)))
      (is (= :session (ffirst (rest fx))))))
  (testing "P4-2.1-03 / P4-2.1-04 ホームの出口"
    (is (re-find #"<p><a data-nav href=\"/works\">" (html {:page :home :fields [{:id 1}]})))
    (is (re-find #"<p><a data-nav href=\"/gantt\">" (html {:page :home :fields [{:id 1}]})))
    (is (not (re-find #"<p><a data-nav href=\"/works\">|<p><a data-nav href=\"/gantt\">"
                      (html {:page :home :fields []})))))
  (testing "作業画面 /works"
    (is (= :works (:page (ui/route-for "/works"))))
    (let [h (html {:page :works
                   :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 10 :name "題A"}]
                   :gantt-rows [{:id 1 :title_id 10 :title "予定A" :start_at "2026-09-18T08:00"
                                 :end_at "2026-09-18T17:00" :work_name "田植え" :field_ids [1]}
                                {:id 2 :title_id nil :title "独立" :start_at "2026-09-19T08:00"
                                 :end_at "2026-09-19T17:00" :work_name nil :field_ids []}]
                   :gantt-selected 1
                   :work-names ["田植え"]})]
      (is (re-find #"works-list" h))
      (is (re-find #"関連する題名" h))
      (is (re-find #"（なし）" h))
      (is (re-find #"対象圃場" h))
      (is (re-find #"id=\"works-add-btn\"" h))
      (is (re-find #"id=\"works-save-btn\"" h))
      (is (re-find #"id=\"works-delete-btn\"" h))
      (is (re-find #"予定A" h))
      (is (re-find #"独立" h)))
    (is (re-find #"まだ作業がありません"
                 (html {:page :works :fields [{:id 1}] :gantt-titles nil :gantt-rows nil})))
    (is (re-find #"作業の編集はパソコン" (html {:page :works :narrow? true})))
    (is (re-find #"圃場が1枚以上" (html {:page :works :fields []})))
    (is (re-find #"works-save-form"
                 (html {:page :works :fields [{:id 1 :name "北"}]
                        :gantt-selected 2
                        :gantt-rows [{:id 2 :title_id nil :title "無題名"
                                      :start_at "2026-09-19T08:00" :end_at "2026-09-19T17:00"
                                      :work_name nil :field_ids []}]})))
    (is (re-find #"<select name=\"title_id\">"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/gantt-title-select-html [{:id 1 :name "題"}] nil true nil))))
    (let [s0 (assoc (ui/init-state) :page :works :session {:email "a"} :kind "user")
          s1 (assoc s0 :fields [{:id 1}])
          empty-fields (ui/fields-loaded s0 {:ok true :fields []})
          with-fields (ui/fields-loaded s1 {:ok true :fields [{:id 1}]})]
      (is (= :api (ffirst (:fx (ui/session-loaded s0 {:ok true :email "a"})))))
      (is (= :html (ffirst (:fx (ui/session-loaded (assoc s0 :narrow? true)
                                                   {:ok true :email "a"})))))
      (is (= :nav (ffirst (:fx (ui/session-loaded (assoc (ui/init-state) :page :works)
                                                  {:ok false})))))
      (is (= :html (ffirst (:fx empty-fields))))
      (is (= :api (ffirst (:fx with-fields))))))
  (testing "P4-2.1-05 / P4-7-07 管理者にガントは無い"
    (is (not (re-find #"href=\"/gantt\"" (html {:page :home :kind "admin"}))))
    (is (= :unknown (:page (ui/route-for "/admin/gantt")))))
  (testing "P4-2.1-06 / P4-5-07 狭い画面"
    (is (re-find #"ガントの編集はパソコンで開いてください"
                 (html {:page :gantt :narrow? true :fields [{:id 1}]})))
    (is (not (re-find #"作業を足す|gantt-circle" (html {:page :gantt :narrow? true :fields [{:id 1}]})))))
  (testing "P4-2.1-07 /map に％は無い"
    (is (not (re-find #"gantt-circle|パーセントサークル"
                      (html {:page :map :place (place) :fields [{:id 1}] :map-mode "paint"
                             :form {:work_name "田植え"}})))))
  (testing "P4-2.2-02 / P4-2.2-04 / P4-7-01 / P4-7-06 未選択・メモはサークル無し"
    (let [open (html {:page :gantt :fields [{:id 1}] :gantt-rows [] :gantt-selected nil})
          memo (html {:page :gantt :fields [{:id 1}]
                      :gantt-rows [{:id 2 :title "メモ" :start_at "2026-09-18T08:00"
                                    :end_at "2026-09-18T09:00" :work_name nil :field_ids []}]
                      :gantt-selected 2})]
      (is (re-find #"gantt-circle" open))
      (is (not (re-find #"data-percent=\"" open)))
      (is (not (re-find #"data-target-ids" open)))
      (is (not (re-find #"data-percent=\"" memo))))
    (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :home
                              :gantt-selected 9 :gantt-progress {:percent 1})
                       [:path {:path "/gantt" :search ""}])]
      (is (nil? (get-in r [:state :gantt-selected])))
      (is (nil? (get-in r [:state :gantt-progress])))
      (is (= "day" (get-in r [:state :gantt-axis])))
      (is (= "time-h" (get-in r [:state :gantt-orient])))))
  (testing "P4-2.2-05 作った直後は選択してよい"
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt)
                       [:gantt-save-result {:ok true :row {:id 7 :title "新"}}])]
      (is (= 7 (get-in r [:state :gantt-selected])))
      (is (= :api (ffirst (:fx r))))))
  (testing "P4-2.3-01 / P4-2.3-02 / P4-2.3-04 / P4-2.3-05 時間軸・8週・向き"
    (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-18T00:00:00Z"))]
      (is (= {:start "2026-09-18T00:00" :end "2026-09-21T00:00" :range "day"}
             (ui/gantt-axis-bounds "day")))
      (is (= {:start "2026-09-18T00:00" :end "2026-09-25T00:00" :range "week"}
             (ui/gantt-axis-bounds "week")))
      (is (= {:start "2026-09-18T00:00" :end "2026-11-13T00:00" :range "weeks8"}
             (ui/gantt-axis-bounds "weeks8")))
      (is (= {:start "2026-09-18T00:00" :end "2026-10-01T00:00" :range "month"}
             (ui/gantt-axis-bounds "month")))
      (is (= {:start "2026-09-18T00:00" :end "2026-12-18T00:00" :range "months3"}
             (ui/gantt-axis-bounds "months3")))
      (is (= {:start "2026-09-18T00:00" :end "2027-03-18T00:00" :range "months6"}
             (ui/gantt-axis-bounds "months6")))
      (is (= "day" (:range (ui/gantt-axis-bounds nil)))))
    (binding [time/*now-fn* (fn [] (Instant/parse "2026-12-15T00:00:00Z"))]
      (is (= {:start "2026-12-15T00:00" :end "2027-01-01T00:00" :range "month"}
             (ui/gantt-axis-bounds "month"))))
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}] :gantt-title-selected 10)
                       [:submit {:act "set-gantt-axis" :form {:axis "week"}}])]
      (is (= "week" (get-in r [:state :gantt-axis])))
      (is (re-find #"data-range=\"week\"" (ui/render (:state r))))
      (is (re-find #"data-select=\"gantt-axis\"" (ui/render (:state r))))
      (is (re-find #"gantt-ticks" (ui/render (:state r))))
      (is (re-find #"gantt-grid" (ui/render (:state r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}] :gantt-title-selected 10)
                       [:submit {:act "set-gantt-axis" :form {:axis "weeks8"}}])]
      (is (= "weeks8" (get-in r [:state :gantt-axis])))
      (is (re-find #"data-range=\"weeks8\"" (ui/render (:state r))))
      (is (re-find #"8週|8 weeks" (ui/render (:state r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}] :gantt-title-selected 10)
                       [:submit {:act "set-gantt-axis" :form {:axis "months3"}}])]
      (is (= "months3" (get-in r [:state :gantt-axis])))
      (is (re-find #"data-range=\"months3\"" (ui/render (:state r))))
      (is (re-find #"3ヶ月|3 months" (ui/render (:state r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}] :gantt-title-selected 10)
                       [:submit {:act "set-gantt-axis" :form {:axis "months6"}}])]
      (is (= "months6" (get-in r [:state :gantt-axis])))
      (is (re-find #"data-range=\"months6\"" (ui/render (:state r))))
      (is (re-find #"6ヶ月|6 months" (ui/render (:state r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}] :gantt-title-selected 10
                                 :gantt-orient "time-h")
                       [:submit {:act "set-gantt-orient" :form {:orient "time-v"}}])]
      (is (= "time-v" (get-in r [:state :gantt-orient])))
      (is (re-find #"gantt-orient-time-v" (ui/render (:state r))))
      (is (re-find #"data-orient=\"time-v\"" (ui/render (:state r))))
      (is (re-find #"data-select=\"gantt-orient\"" (ui/render (:state r)))))
    (is (= "time-h" (get-in (ui/handle (assoc (ui/init-state) :page :gantt)
                                       [:submit {:act "set-gantt-orient" :form {:orient "nope"}}])
                            [:state :gantt-orient])))
    (is (= "day" (get-in (ui/handle (assoc (ui/init-state) :page :gantt)
                                    [:submit {:act "set-gantt-axis" :form {:axis "nope"}}])
                         [:state :gantt-axis]))))
  (testing "P4-2.3-03 範囲外の行も編集欄がある"
    (is (re-find #"save-gantt-row"
                 (html {:page :gantt :fields [{:id 1}]
                        :gantt-rows [{:id 1 :title "遠い" :start_at "2030-01-01T08:00"
                                      :end_at "2030-01-01T09:00" :field_ids []}]
                        :gantt-selected 1}))))
  (testing "P4-3.4-01 / P4-3.4-02 地図の data 属性"
    (let [sel (html {:page :gantt :fields [{:id 1} {:id 2}]
                     :gantt-rows [{:id 1 :title "対象" :start_at "2026-09-18T08:00"
                                   :end_at "2026-09-18T09:00" :work_name "田植え" :field_ids [1]}]
                     :gantt-selected 1
                     :gantt-progress {:ok true :applicable true :percent 10
                                      :fields [{:id 1 :status "partial"}]}})
          none (html {:page :gantt :fields [{:id 1}] :gantt-selected nil})]
      (is (re-find #"data-work-name=\"田植え\"" sel))
      (is (re-find #"#e8e8e8" (pr-str ui/paint-colors)))
      (is (not (re-find #"data-work-name" none)))))
  (testing "P4-3.3-05 / P4-3.3-06 / P4-6-07 手入力％は無い。全面完了は地図側"
    (let [h (html {:page :gantt :fields [{:id 1}]})]
      (is (not (re-find #"name=\"percent\"|日誌" h)))
      (is (not (re-find #"全面完了|ブラシ" h)))))
  (testing "P4-3.3-07 保存ボタン id"
    (is (re-find #"id=\"gantt-save-btn\""
                 (html {:page :gantt :fields [{:id 1}]
                        :gantt-rows [{:id 1 :title "行" :start_at "2026-09-18T08:00"
                                      :end_at "2026-09-18T09:00" :field_ids []}]
                        :gantt-selected 1})))
    (is (re-find #"id=\"gantt-add-btn\"" (html {:page :gantt :fields [{:id 1}]}))))
  (testing "P4-5-04〜06 文言"
    (is (re-find #"圃場が1枚以上あるときだけ、ガントを使えます"
                 (html {:page :gantt :fields []})))
    (is (= "対象圃場がある行は、作業名を入れてください" (:gantt-work-needed ui/messages)))
    (is (= "終了は開始より後にしてください" (:gantt-time-order ui/messages))))
  (testing "P4-6 / P4-6-01〜10 作らないもの"
    (let [h (html {:page :gantt :fields [{:id 1}]})
          doc (slurp (io/file "docs/詳細試験仕様書_工程4.md"))]
      (is (not (re-find #"指示|日誌|関係を切|言語切替" h)))
      (is (= [:gantt-delete "1"] (http/match-api :delete "/api/user/gantt/1")))
      (is (nil? (http/match-api :put "/api/user/work-names")))
      (is (some? (http/match-api :post "/api/user/orders")))
      (doseq [id ["P4-6-01" "P4-6-02" "P4-6-03" "P4-6-04" "P4-6-05"
                  "P4-6-06" "P4-6-07" "P4-6-08" "P4-6-09" "P4-6-10"]]
        (is (re-find (re-pattern id) doc)))))
  (testing "P4-3.3-01 複数行をまとめた％は出さない"
    (is (not (re-find #"まとめた" (html {:page :gantt :fields [{:id 1}]
                                          :gantt-rows [{:id 1} {:id 2}]
                                          :gantt-selected 1
                                          :gantt-progress {:applicable true :percent 1}}))))))

(deftest p4-api-and-rules
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid usid2 uid uid2]} (farm sys)
            f1 (add-field app usid "北" tu/square)
            f2 (add-field app usid "南" tu/square-east)
            fo (add-field app usid2 "他人" tu/square)
            id1 (:id f1)
            id2 (:id f2)
            oid (:id fo)
            tid (:id (make-title app usid "題A"))]
        (testing "P4-2.4-10 管理者は forbidden"
          (is (= 403 (:status (tu/get-path app "/api/user/gantt" "admin" asid))))
          (is (= 403 (:status (tu/post-json app "/api/user/gantt"
                                            (row-body tid "a" "2026-09-18T08:00" "2026-09-18T09:00")
                                            "admin" asid))))
          (is (= 403 (:status (tu/get-path app "/api/user/gantt/titles" "admin" asid)))))
        (testing "題名 HTTP"
          (let [listed (tu/parse (tu/get-path app "/api/user/gantt/titles" "user" usid))
                created (tu/parse (tu/post-json app "/api/user/gantt/titles" {:name "題HTTP"} "user" usid))
                cid (get-in created [:title :id])
                ren (tu/parse (tu/put-json app (str "/api/user/gantt/titles/" cid)
                                           {:name "題HTTP2"} "user" usid))
                del (tu/parse (tu/delete-path app (str "/api/user/gantt/titles/" cid) "user" usid))
                bad (tu/parse (tu/post-json app "/api/user/gantt/titles" {:name "  "} "user" usid))
                boom (tu/parse (app (-> (mock/request :post "/api/user/gantt/titles")
                                        (mock/content-type "application/json")
                                        (mock/body "not-json")
                                        (tu/as-user "user" usid))))
                boom2 (tu/parse (app (-> (mock/request :put (str "/api/user/gantt/titles/" tid))
                                         (mock/content-type "application/json")
                                         (mock/body "not-json")
                                         (tu/as-user "user" usid))))]
            (is (true? (:ok listed)))
            (is (some #(= "題A" (:name %)) (:titles listed)))
            (is (= "題HTTP2" (get-in ren [:title :name])))
            (is (true? (:ok del)))
            (is (= "title_required" (:code bad)))
            (is (= "title_required" (:code boom)))
            (is (= "title_required" (:code boom2)))
            (is (= [:gantt-titles-get] (http/match-api :get "/api/user/gantt/titles")))
            (is (= [:gantt-titles-put "9"] (http/match-api :put "/api/user/gantt/titles/9")))
            (is (= [:gantt-titles-delete "9"] (http/match-api :delete "/api/user/gantt/titles/9")))
            (is (nil? (http/match-api :get "/api/user/gantt/titles/9")))))
        (testing "題名 HTTP 失敗"
          (let [pw0 (tu/invite-pw app asid "p4-title-zero@example.com")
                sid0 (tu/user-sid app "p4-title-zero@example.com" pw0)]
            (is (= "no_fields" (:code (tu/parse (tu/get-path app "/api/user/gantt/titles" "user" sid0)))))
            (is (= "no_fields" (:code (tu/parse (tu/put-json app (str "/api/user/gantt/titles/" tid)
                                                             {:name "x"} "user" sid0)))))
            (is (= "no_fields" (:code (tu/parse (tu/delete-path app (str "/api/user/gantt/titles/" tid)
                                                                "user" sid0))))))
          (is (= "title_not_found"
                 (:code (tu/parse (tu/put-json app "/api/user/gantt/titles/99999"
                                               {:name "x"} "user" usid)))))
          (is (= "title_not_found"
                 (:code (tu/parse (tu/delete-path app "/api/user/gantt/titles/99999"
                                                  "user" usid))))))
        (testing "P4-2.4-11 / P4-2.4-c01 / P4-7-05 圃場0"
          (let [pw0 (tu/invite-pw app asid "p4-zero@example.com")
                sid0 (tu/user-sid app "p4-zero@example.com" pw0)]
            (is (= "no_fields" (:code (tu/parse (tu/get-path app "/api/user/gantt" "user" sid0)))))
            (is (= "no_fields" (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                              (row-body tid "x" "2026-09-18T08:00" "2026-09-18T09:00")
                                                              "user" sid0)))))))
        (testing "P4-2.4-01 / P4-2.4-02 / P4-2.4-03 / P4-7-04 作成・一覧・更新・重なり・ソフト削除"
          (let [a (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "後" "2026-09-18T12:00" "2026-09-18T13:00")
                                          "user" usid))
                b (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "先" "2026-09-18T08:00" "2026-09-18T17:00" "田植え" [id1])
                                          "user" usid))
                c (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "重なり" "2026-09-18T08:00" "2026-09-18T10:00")
                                          "user" usid))
                listed (tu/parse (tu/get-path app "/api/user/gantt" "user" usid))]
            (is (true? (:ok a)))
            (is (nil? (get-in a [:row :work_name])))
            (is (= [] (get-in a [:row :field_ids])))
            (is (= [id1] (get-in b [:row :field_ids])))
            (is (= ["先" "重なり" "後"] (mapv :title (:rows listed))))
            (let [gid (get-in b [:row :id])
                  u (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                           (row-body tid "直した" "2026-09-18T08:00" "2026-09-18T17:00" "田植え" [id1 id2])
                                           "user" usid))]
              (is (= "直した" (get-in u [:row :title])))
              (is (= [id1 id2] (get-in u [:row :field_ids]))))
            (let [gid (get-in b [:row :id])
                  del (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid) "user" usid))
                  listed2 (tu/parse (tu/get-path app "/api/user/gantt" "user" usid))]
              (is (true? (:ok del)))
              (is (not (some #(= gid (:id %)) (:rows listed2)))))))
        (testing "P4-2.4-04 / P4-2.4-05 / P4-2.4-15 / P4-3.3-02〜04 progress"
          (let [memo (tu/parse (tu/post-json app "/api/user/gantt"
                                             (row-body tid "メモ" "2026-09-19T08:00" "2026-09-19T09:00")
                                             "user" usid))
                tgt (tu/parse (tu/post-json app "/api/user/gantt"
                                            (row-body tid "％" "2026-09-19T10:00" "2026-09-19T11:00" "田植え" [id1 id2])
                                            "user" usid))
                mid (get-in memo [:row :id])
                gid (get-in tgt [:row :id])
                na (tu/parse (tu/get-path app (str "/api/user/gantt/" mid "/progress") "user" usid))
                p0 (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress") "user" usid))]
            (is (false? (:applicable na)))
            (is (nil? (:percent na)))
            (is (true? (:applicable p0)))
            (is (contains? p0 :numerator_m2))
            (is (contains? p0 :denominator_m2))
            (is (every? #(contains? % :id) (:fields p0)))
            (is (every? #(contains? % :status) (:fields p0)))
            (is (every? #(contains? % :area_m2) (:fields p0)))
            (is (every? #(contains? % :field_area_m2) (:fields p0)))
            (is (= (:denominator_m2 p0)
                   (reduce + 0 (map :field_area_m2 (:fields p0)))))
            (tu/post-json app "/api/user/paints"
                          {:field_id id1 :work_name "田植え" :geojson tu/square-inner}
                          "user" usid)
            (let [pp (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress") "user" usid))]
              (is (pos? (:numerator_m2 pp)))
              (is (<= (:numerator_m2 pp) (:denominator_m2 pp)))
              (is (< (:percent pp) 100)))
            (tu/post-json app (str "/api/user/fields/" id1 "/complete")
                          {:work_name "田植え"} "user" usid)
            (tu/post-json app (str "/api/user/fields/" id2 "/complete")
                          {:work_name "田植え"} "user" usid)
            (let [pd (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress") "user" usid))]
              (is (= 100 (:percent pd)))
              (is (every? #(= "done" (:status %)) (:fields pd))))
            (is (= 99 (gantt/progress-percent 100 100 false)))))
        (testing "P4-2.4-06 / P4-2.4-08 / P4-2.4-09 / P4-3.5-01 候補"
          (tu/post-json app "/api/user/gantt"
                        (row-body tid "候補" "2026-09-20T08:00" "2026-09-20T09:00" "ガント名" [])
                        "user" usid)
          (tu/post-json app "/api/user/paints"
                        {:field_id oid :work_name "秘密" :geojson tu/square-inner}
                        "user" usid2)
          (let [cands (:work_names (tu/parse (tu/get-path app "/api/user/work-name-candidates" "user" usid)))
                paints-only (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid)))]
            (is (some #{"ガント名"} cands))
            (is (some #{"田植え"} cands))
            (is (not (some #{"秘密"} cands)))
            (is (not (some #{"ガント名"} paints-only)))
            (is (= cands (sort cands))))
          (let [fx (:fx (ui/basemaps-loaded (assoc (ui/init-state) :page :map :fields [{:id 1}])
                                            {:ok true :basemaps []}))]
            (is (some #(= "/api/user/work-name-candidates" (nth % 2 nil)) fx))))
        (testing "P4-2.4-07 ソフト削除 DELETE"
          (is (= [:gantt-delete "1"] (http/match-api :delete "/api/user/gantt/1"))))
        (testing "P4-2.4-c02〜c09 失敗 code"
          (is (= "gantt_not_found"
                 (:code (tu/parse (tu/put-json app "/api/user/gantt/99999"
                                               (row-body tid "a" "2026-09-18T08:00" "2026-09-18T09:00")
                                               "user" usid)))))
          (is (= "gantt_not_found"
                 (:code (tu/parse (tu/get-path app "/api/user/gantt/99999/progress" "user" usid)))))
          (is (= "title_required"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "  " "2026-09-18T08:00" "2026-09-18T09:00")
                                                "user" usid)))))
          (is (= "title_too_long"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid (apply str (repeat 201 "あ"))
                                                          "2026-09-18T08:00" "2026-09-18T09:00")
                                                "user" usid)))))
          (is (= "time_invalid"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                {:title_id tid :title "t" :start_at "bad" :end_at "2026-09-18T09:00"}
                                                "user" usid)))))
          (is (= "time_order"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "t" "2026-09-18T09:00" "2026-09-18T08:00")
                                                "user" usid)))))
          (is (= "work_name_required"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "t" "2026-09-18T08:00" "2026-09-18T09:00" nil [id1])
                                                "user" usid)))))
          (is (= "work_name_too_long"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "t" "2026-09-18T08:00" "2026-09-18T09:00"
                                                          (apply str (repeat 101 "あ")) [])
                                                "user" usid)))))
          (is (= "field_not_found"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "t" "2026-09-18T08:00" "2026-09-18T09:00" "田植え" [oid])
                                                "user" usid)))))
          (is (= "title_required"
                 (:code (tu/parse (app (-> (mock/request :post "/api/user/gantt")
                                           (mock/content-type "application/json")
                                           (mock/body "not-json")
                                           (tu/as-user "user" usid)))))))
          (let [gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "put壊" "2026-09-18T14:00" "2026-09-18T15:00")
                                                    "user" usid))
                            [:row :id])]
            (is (= "title_required"
                   (:code (tu/parse (app (-> (mock/request :put (str "/api/user/gantt/" gid))
                                             (mock/content-type "application/json")
                                             (mock/body "not-json")
                                             (tu/as-user "user" usid)))))))))
        (testing "P4-2.4-12 / P4-4-04 圃場削除で対象から外す"
          (let [r (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "残す" "2026-09-22T08:00" "2026-09-22T09:00" "田植え" [id2])
                                          "user" usid))
                gid (get-in r [:row :id])]
            (is (true? (:ok (tu/parse (tu/delete-path app (str "/api/user/fields/" id2) "user" usid)))))
            (let [row (first (filter #(= gid (:id %))
                                     (:rows (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))))]
              (is (= [] (:field_ids row)))
              (is (= "残す" (:title row))))))
        (testing "P4-2.4-13 split で対象から外れる"
          (let [fs (add-field app usid "分割元" tu/square)
                fid (:id fs)
                r (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "分割行" "2026-09-23T08:00" "2026-09-23T09:00" "田植え" [fid])
                                          "user" usid))
                gid (get-in r [:row :id])]
            (is (true? (:ok (fields/split-field sys uid fid {:polygons [tu/square tu/square-east]}))))
            (let [row (first (filter #(= gid (:id %))
                                     (:rows (gantt/list-rows sys uid))))]
              (is (= [] (:field_ids row))))))
        (testing "P4-2.4-14 merge で消える側を外す"
          (let [a (add-field app usid "合A" tu/square)
                b (add-field app usid "合B" tu/square-east)
                ra (tu/parse (tu/post-json app "/api/user/gantt"
                                           (row-body tid "合A行" "2026-09-24T08:00" "2026-09-24T09:00" "田植え" [(:id a)])
                                           "user" usid))
                rb (tu/parse (tu/post-json app "/api/user/gantt"
                                           (row-body tid "合B行" "2026-09-24T10:00" "2026-09-24T11:00" "田植え" [(:id b)])
                                           "user" usid))
                ga (get-in ra [:row :id])
                gb (get-in rb [:row :id])]
            (is (true? (:ok (fields/merge-fields sys uid {:ids [(:id a) (:id b)] :keep_id (:id a)}))))
            (let [rows (:rows (gantt/list-rows sys uid))
                  row-a (first (filter #(= ga (:id %)) rows))
                  row-b (first (filter #(= gb (:id %)) rows))]
              (is (= [(:id a)] (:field_ids row-a)))
              (is (= [] (:field_ids row-b))))))
        (testing "P4-3.1-01 0枚になっても行は残り、戻れば使える"
          (let [only (add-field app usid "最後" tu/square)
                r (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "残行" "2026-09-25T08:00" "2026-09-25T09:00")
                                          "user" usid))
                gid (get-in r [:row :id])
                others (filter #(not= (:id only) (:id %))
                               (:fields (tu/parse (tu/get-path app "/api/user/fields" "user" usid))))]
            (doseq [f others]
              (tu/delete-path app (str "/api/user/fields/" (:id f)) "user" usid))
            (tu/delete-path app (str "/api/user/fields/" (:id only)) "user" usid)
            (is (= "no_fields" (:code (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))))
            (add-field app usid "復活" tu/square)
            (is (some #(= gid (:id %))
                      (:rows (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))))))
        (testing "P4-3.1-02 / P4-3.2 / P4-7-03"
          (is (= "field_not_found"
                 (:code (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "他" "2026-09-26T08:00" "2026-09-26T09:00" "田植え" [oid])
                                                "user" usid)))))
          (is (= "time_order"
                 (:code (gantt/create-row sys uid (row-body tid "同" "2026-09-26T08:00" "2026-09-26T08:00")))))
          (let [m (gantt/create-row sys uid (row-body tid "作業だけ" "2026-09-26T08:00" "2026-09-26T09:00" "ラベル" []))]
            (is (true? (:ok m)))
            (is (= "ラベル" (get-in m [:row :work_name])))
            (is (false? (:applicable (gantt/row-progress sys uid (get-in m [:row :id])))))))
        (testing "P4-3.2-07 新規初期値"
          (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-18T00:00:00Z"))]
            (is (= "新しい作業" (:title (gantt/default-new-row))))
            (is (= "2026-09-18T08:00" (:start_at (gantt/default-new-row))))
            (is (= "2026-09-18T17:00" (:end_at (gantt/default-new-row))))))
        (testing "P4-4-01〜03 表"
          (let [names (tu/table-names (:ds sys))
                cols (db/table-columns (:ds sys) "gantt_rows")
                tcols (db/table-columns (:ds sys) "gantt_targets")]
            (is (contains? names "fields"))
            (is (contains? names "paints"))
            (is (contains? names "gantt_rows"))
            (is (contains? names "gantt_targets"))
            (is (every? cols ["user_id" "title" "start_at" "end_at" "work_name" "created_at" "updated_at"]))
            (is (not (contains? cols "deleted")))
            (is (not (contains? cols "order_id")))
            (is (not (contains? cols "percent")))
            (is (every? tcols ["gantt_id" "field_id"]))))
        (testing "P4-7-02 ％と色が塗りと一致"
          (let [f (add-field app usid "色" tu/square)
                r (tu/parse (tu/post-json app "/api/user/gantt"
                                          (row-body tid "色行" "2026-09-27T08:00" "2026-09-27T09:00" "色作業" [(:id f)])
                                          "user" usid))
                gid (get-in r [:row :id])]
            (tu/post-json app "/api/user/paints"
                          {:field_id (:id f) :work_name "色作業" :geojson tu/square-inner}
                          "user" usid)
            (let [prog (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress") "user" usid))
                  paints (tu/parse (tu/get-query app "/api/user/paints" {:work_name "色作業"} "user" usid))
                  st (:status (first (filter #(= (:id f) (:id %)) (:fields paints))))]
              (is (= st (:status (first (:fields prog)))))
              (is (= "partial" st)))))))))

(deftest p4-ui-handlers
  (testing "P4 UI handlers cover gantt acts"
    (let [s (assoc (ui/init-state) :page :gantt :session {:email "a"} :kind "user"
                   :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 10 :name "題A"}]
                   :gantt-title-selected 10
                   :gantt-rows [{:id 3 :title_id 10 :title "行" :start_at "2026-09-18T08:00"
                                 :end_at "2026-09-18T09:00" :work_name "田植え" :field_ids [1]}
                                {:id 4 :title_id 10 :title "メモ" :start_at "2026-09-18T10:00"
                                 :end_at "2026-09-18T11:00" :field_ids []}])]
      (is (= :api (tu/fx-op s [:submit {:act "select-gantt-row" :form {:id "3"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "select-gantt-row" :form {:id "4"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "add-gantt-row"
                                        :form {:title "" :start_at "2026-09-18T08:00" :end_at "2026-09-18T09:00"}}])))
      (is (= :html (tu/fx-op s [:submit {:act "add-gantt-row" :form {:title "x"}}])))
      (is (= :html (tu/fx-op (dissoc s :gantt-title-selected)
                             [:submit {:act "add-gantt-row"
                                       :form {:title "x" :start_at "2026-09-18T08:00" :end_at "2026-09-18T09:00"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "add-gantt-title" :form {:name "新題"}}])))
      (is (= :html (tu/fx-op s [:submit {:act "add-gantt-title" :form {:name " "}}])))
      (is (= :html (tu/fx-op s [:submit {:act "select-gantt-title" :form {:id "10"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "save-gantt-title" :form {:id "10" :name "題改"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "save-gantt-title" :form {:name "題選"}}])))
      (is (= :html (tu/fx-op s [:submit {:act "save-gantt-title" :form {:id "" :name "題"}}])))
      (is (= :html (tu/fx-op s [:submit {:act "save-gantt-title" :form {:id "10" :name " "}}])))
      (is (= :api (tu/fx-op s [:submit {:act "delete-gantt-title" :form {:id "10"}}])))
      (is (= :api (tu/fx-op s [:submit {:act "delete-gantt-title" :form {}}])))
      (is (= :html (tu/fx-op (dissoc s :gantt-title-selected)
                             [:submit {:act "delete-gantt-title" :form {}}])))
      (is (= :api (tu/fx-op s [:submit {:act "add-gantt-row"
                                        :form {:title_id "10" :title "明示"
                                               :start_at "2026-09-18T08:00" :end_at "2026-09-18T09:00"}}])))
      (is (= :html (tu/fx-op s [:submit {:act "save-gantt-row"
                                         :form {:id "3" :title "行" :start_at "2026-09-18T08:00"
                                                :end_at "2026-09-18T09:00" :work_name "田植え"
                                                :field_ids ["1"] :title_id ""}}])))
      (let [ws (assoc s :page :works)]
        (is (= :api (tu/fx-op ws [:submit {:act "add-gantt-row"
                                           :form {:title "独立" :start_at "2026-09-18T08:00"
                                                  :end_at "2026-09-18T09:00" :title_id ""}}])))
        (is (= :api (tu/fx-op ws [:submit {:act "save-gantt-row"
                                           :form {:id "3" :title "行" :start_at "2026-09-18T08:00"
                                                  :end_at "2026-09-18T09:00" :work_name "田植え"
                                                  :field_ids ["1"] :title_id ""}}]))))
      (is (= :api (tu/fx-op s [:gantt-title-save-result {:ok true :title {:id 11 :name "x"}}])))
      (is (= :html (tu/fx-op s [:gantt-title-save-result {:ok false :code "title_required"}])))
      (is (= :api (tu/fx-op s [:gantt-title-delete-result {:ok true}])))
      (is (= :html (tu/fx-op s [:gantt-title-delete-result {:ok false :code "title_not_found"}])))
      (is (= "先に題名を選ぶか、下で題名を足してください" (ui/code-message "title_not_found")))
      (is (re-find #"題名を足す|gantt-title-add" (ui/render s)))
      (is (re-find #"作業を足す" (ui/render s)))
      (is (re-find #"gantt-titles" (ui/render (dissoc s :gantt-titles))))
      (is (= :html (tu/fx-op s [:gantt-loaded {:ok true :titles [] :rows []}])))
      (is (= 10 (get-in (ui/handle (dissoc s :gantt-title-selected)
                                   [:gantt-loaded {:ok true
                                                   :titles [{:id 10 :name "題A"}]
                                                   :rows []}])
                        [:state :gantt-title-selected])))
      (is (= 10 (get-in (ui/handle (assoc s :gantt-title-selected 99)
                                   [:gantt-loaded {:ok true
                                                   :titles [{:id 10 :name "題A"}]
                                                   :rows []}])
                        [:state :gantt-title-selected])))
      (is (= 10 (get-in (ui/handle (assoc s :gantt-title-selected 10)
                                   [:gantt-loaded {:ok true
                                                   :titles [{:id 1 :name "他"} {:id 10 :name "題A"}]
                                                   :rows []}])
                        [:state :gantt-title-selected])))
      (is (= :api (tu/fx-op (assoc s :gantt-selected 3 :gantt-title-selected 10)
                            [:gantt-loaded {:ok true
                                            :titles [{:id 10 :name "題A"}]
                                            :rows [{:id 1 :title_id 10 :title "他"
                                                    :start_at "2026-09-18T08:00"
                                                    :end_at "2026-09-18T09:00"
                                                    :field_ids []}
                                                   {:id 3 :title_id 10 :title "行"
                                                    :start_at "2026-09-18T08:00"
                                                    :end_at "2026-09-18T09:00"
                                                    :work_name "田植え" :field_ids [1]}]}])))
      (is (nil? (get-in (ui/handle (assoc s :gantt-selected 3 :gantt-title-selected 10)
                                   [:gantt-loaded {:ok true
                                                   :titles [{:id 10 :name "題A"}]
                                                   :rows [{:id 3 :title_id 99 :title "行"
                                                           :start_at "2026-09-18T08:00"
                                                           :end_at "2026-09-18T09:00"
                                                           :work_name "田植え" :field_ids [1]}]}])
                        [:state :gantt-selected])))
      (is (nil? (get-in (ui/handle (assoc s :gantt-selected 3)
                                   [:gantt-loaded {:ok true :titles [] :rows
                                                   [{:id 3 :title_id 10 :title "行"
                                                     :start_at "2026-09-18T08:00"
                                                     :end_at "2026-09-18T09:00"
                                                     :field_ids []}]}])
                        [:state :gantt-selected])))
      (is (= :html (tu/fx-op s [:submit {:act "select-gantt-title" :form {:id ""}}])))
      (is (nil? (#'ui/submit-gantt-act s {} "nope")))
      (is (= :api (tu/fx-op (assoc s :gantt-selected 3)
                            [:submit {:act "save-gantt-row"
                                      :form {:id "3" :title "行" :start_at "2026-09-18T08:00"
                                             :end_at "2026-09-18T09:00" :work_name "田植え"
                                             :field_ids ["1"]}}])))
      (is (re-find #"作業名を入れてください"
                   (get-in (ui/handle s [:submit {:act "save-gantt-row"
                                                  :form {:id "3" :title "行" :start_at "2026-09-18T08:00"
                                                         :end_at "2026-09-18T09:00" :field_ids ["1"]}}])
                           [:state :flash :text])))
      (is (re-find #"その予定はありません"
                   (get-in (ui/handle s [:submit {:act "save-gantt-row"
                                                  :form {:title "行" :start_at "2026-09-18T08:00"
                                                         :end_at "2026-09-18T09:00"}}])
                           [:state :flash :text])))
      (is (= :html (tu/fx-op s [:gantt-loaded {:ok false :code "no_fields"}])))
      (is (= :html (tu/fx-op s [:gantt-loaded {:ok true :rows []}])))
      (is (= :api (tu/fx-op (assoc s :gantt-selected 3)
                            [:gantt-loaded {:ok true
                                            :titles (:gantt-titles s)
                                            :rows (:gantt-rows s)}])))
      (is (= :html (tu/fx-op s [:gantt-save-result {:ok false :code "work_name_required"}])))
      (is (= :html (tu/fx-op s [:gantt-save-result {:ok false :code "time_order"}])))
      (is (= "題名を入れてください" (ui/code-message "title_required")))
      (is (= "題名は200文字以内にしてください" (ui/code-message "title_too_long")))
      (is (= "開始と終了は分までの日時にしてください" (ui/code-message "time_invalid")))
      (is (= :html (tu/fx-op s [:home-fields-loaded {:ok true :fields [{:id 1}]}])))
      (is (= :html (tu/fx-op s [:home-fields-loaded {:ok true}])))
      (is (= :html (tu/fx-op (assoc s :page :home)
                             [:fields-loaded {:ok true :fields [{:id 1}]}])))
      (is (= :api (tu/fx-op s [:submit {:act "save-gantt-row"
                                        :form {:id "3" :title "行" :start_at "2026-09-18T08:00"
                                               :end_at "2026-09-18T09:00" :work_name "田植え"
                                               :field_ids (list "1" "  ")}}])))
      (is (= :api (tu/fx-op s [:submit {:act "save-gantt-row"
                                        :form {:id "4" :title "メモ" :start_at "2026-09-18T10:00"
                                               :end_at "2026-09-18T11:00" :work_name nil
                                               :field_ids ""}}])))
      (is (re-find #"gantt-circle"
                   (ui/render (assoc s :gantt-selected 3
                                     :gantt-progress {:ok true :applicable false}))))
      (is (re-find #"data-percent=\""
                   (ui/render (assoc s :gantt-selected 3
                                     :gantt-progress {:ok true :applicable true :percent 0}))))
      (is (= :html (tu/fx-op s [:gantt-loaded {:ok true}])))
      (is (= :api (tu/fx-op (assoc s :gantt-selected 3)
                            [:gantt-loaded {:ok true
                                            :titles (:gantt-titles s)
                                            :rows [{:id 3 :title_id 10 :title "行" :start_at "2026-09-18T08:00"
                                                    :end_at "2026-09-18T09:00"
                                                    :work_name nil :field_ids [1]}]}])))
      (is (re-find #"data-range=\"day\""
                   (ui/render (assoc s :gantt-axis nil))))
      (is (= "day" (get-in (ui/handle s [:submit {:act "set-gantt-axis" :form {}}])
                           [:state :gantt-axis])))
      (is (= :html (tu/fx-op s [:submit {:act "select-gantt-row" :form {}}])))
      (is (= :api (tu/fx-op s [:submit {:act "add-gantt-row"
                                        :form {:start_at "2026-09-18T08:00" :end_at "2026-09-18T09:00"}}])))
      (is (= :html (tu/fx-op (dissoc s :gantt-selected)
                             [:submit {:act "save-gantt-row"
                                       :form {:title "行" :start_at "2026-09-18T08:00"
                                              :end_at "2026-09-18T09:00"}}])))
      (is (true? (boolean (ui/handle (assoc s :gantt-rows
                                            [{:id 1 :title_id 10 :title "a" :field_ids [1] :work_name nil}])
                                     [:submit {:act "select-gantt-row" :form {:id "1"}}]))))
      (is (re-find #"gantt-row"
                   (ui/render (assoc s :gantt-rows [{:id nil :title_id 10 :title "無ID" :start_at "2026-09-18T08:00"
                                                     :end_at "2026-09-18T09:00" :field_ids []}]
                                     :gantt-selected 1))))
      (is (re-find #"gantt-circle"
                   (ui/render (assoc s :gantt-selected 3 :gantt-progress nil))))
      (is (re-find #"gantt-circle"
                   (ui/render (assoc s :gantt-selected 4
                                     :gantt-progress {:ok true :applicable true :percent 1}))))
      (is (= :api (tu/fx-op (assoc (ui/init-state) :page :home :kind "user" :session {:email "a"})
                            [:session-loaded {:ok true :email "a"}])))
      (is (= :html (tu/fx-op (assoc (ui/init-state) :page :home :kind "admin" :session {:email "a"})
                             [:session-loaded {:ok true :email "a"}])))
      (is (not= :api (tu/fx-op (assoc (ui/init-state) :page :home :kind "user")
                               [:session-loaded {:ok false}])))
      (is (= :html (tu/fx-op s [:gantt-progress-loaded {:ok true :applicable true :percent 1}])))
      (is (= :html (tu/fx-op s [:gantt-progress-loaded {:ok false :code "gantt_not_found"}])))
      (is (re-find #"圃場が1枚以上" (ui/render (assoc s :fields [] :page :gantt))))
      (is (= :html (tu/fx-op (assoc s :fields [] :page :gantt)
                             [:fields-loaded {:ok true :fields []}])))
      (let [fx (:fx (ui/basemaps-loaded (assoc s :page :gantt :fields [{:id 1}])
                                        {:ok true :basemaps []}))]
        (is (some #(= "/api/user/gantt" (nth % 2 nil)) fx))
        (is (some #(= "/api/user/work-name-candidates" (nth % 2 nil)) fx)))
      (is (= :html (tu/fx-op s [:submit {:act "save-gantt-row"
                                         :form {:id "3" :title "行" :start_at "" :end_at ""
                                                :work_name "田植え" :field_ids ["1"]}}])))
      (is (= :html (tu/fx-op s [:submit {:act "save-gantt-row"
                                         :form {:id "4" :title "メモ" :start_at "2026-09-18T10:00"
                                                :end_at "2026-09-18T11:00"
                                                :field_ids ["1" "2"]
                                                :work_name "  "}}])))
      (let [r (ui/handle s [:submit {:act "save-gantt-row"
                                     :form {:id "4" :title "メモ" :start_at "2026-09-18T10:00"
                                            :end_at "2026-09-18T11:00" :work_name ""
                                            :field_ids "1"}}])]
        (is (re-find #"作業名を入れてください" (get-in r [:state :flash :text])))))))

(deftest p4-progress-history-and-admin
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid uid]} (farm sys)
            f (add-field app usid "北" tu/square)
            fid (:id f)
            tid (:id (make-title app usid "題歴"))
            created (tu/parse (tu/post-json app "/api/user/gantt"
                                            (row-body tid "歴" "2026-09-17T08:00" "2026-09-17T09:00" "田植え" [fid])
                                            "user" usid))
            gid (get-in created [:row :id])]
        (is (= [:gantt-progress-days (str gid)]
               (http/match-api :get (str "/api/user/gantt/" gid "/progress-days"))))
        (is (= [:admin-gantt-progress-finalize]
               (http/match-api :post "/api/admin/gantt/progress/finalize")))
        (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-19T01:00:00Z"))]
          (let [sess (tu/parse (tu/get-path app "/api/user/session" "user" usid))
                days (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress-days") "user" usid))
                fin (tu/parse (tu/post-json app "/api/admin/gantt/progress/finalize"
                                            {:day "2026-09-18"} "admin" asid))
                fin-user (tu/parse (tu/post-json app "/api/admin/gantt/progress/finalize"
                                                 {:day "2026-09-17" :email "p4@example.com"}
                                                 "admin" asid))
                miss (tu/parse (tu/post-json app "/api/admin/gantt/progress/finalize"
                                             {:email "nope@example.com"} "admin" asid))
                unauth (tu/parse (tu/post-json app "/api/admin/gantt/progress/finalize" {}))
                bad (tu/parse (app (tu/as-user (-> (mock/request :post "/api/admin/gantt/progress/finalize")
                                                   (mock/content-type "application/json")
                                                   (mock/body "{"))
                                           "admin" asid)))]
            (is (true? (:ok sess)))
            (is (true? (:ok days)))
            (is (seq (:days days)))
            (is (true? (:ok fin)))
            (is (true? (:ok fin-user)))
            (is (= "user_not_found" (:code miss)))
            (is (= "unauthorized" (:code unauth)))
            (is (= "time_invalid" (:code bad)))))
        (is (true? (:ok (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid) "user" usid)))))
        (is (= "gantt_not_found"
               (:code (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid) "user" usid)))))
        (is (= "gantt_not_found"
               (:code (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress-days") "user" usid)))))
        (is (nil? (http/match-api :get "/api/user/gantt/1")))
        (is (nil? (http/match-api :post "/api/user/gantt/1/progress-days")))
        (let [base (assoc (ui/init-state) :kind "admin" :session {:email "a"} :page :gantt-progress
                          :form {:day "2026-09-18" :email "a@b.c"})
              empty-form (assoc (ui/init-state) :kind "admin" :session {:email "a"} :page :gantt-progress)
              with-days (assoc (ui/init-state) :page :gantt :kind "user" :session {:email "a"}
                               :fields [{:id 1 :name "北"}]
                               :gantt-titles [{:id 10 :name "題A"}]
                               :gantt-title-selected 10
                               :gantt-rows [{:id 1 :title_id 10 :title "行" :start_at "2026-09-18T08:00"
                                             :end_at "2026-09-18T09:00" :work_name "田植え" :field_ids [1]}]
                               :gantt-selected 1
                               :gantt-progress-days [{:day "2026-09-17" :percent 10 :applicable true}
                                                     {:day "2026-09-18" :percent nil :applicable false}])]
          (is (= :gantt-progress (:page (ui/route-for "/admin/gantt-progress"))))
          (is (true? (ui/needs-auth? :gantt-progress)))
          (is (re-find #"進捗確定" (ui/render base)))
          (is (re-find #"進捗確定" (ui/render empty-form)))
          (is (re-find #"進捗確定" (ui/render (assoc base :gantt-finalize-result {:finalized 2 :day "2026-09-18"}))))
          (is (re-find #"振り返り" (ui/render with-days)))
          (is (re-find #"—" (ui/render with-days)))
          (is (= "その利用者はいません" (ui/code-message "user_not_found")))
          (is (= :api (ffirst (:fx (ui/handle with-days [:submit {:act "delete-gantt-row" :form {:id "1"}}])))))
          (is (= :html (ffirst (:fx (ui/handle (assoc with-days :gantt-selected nil)
                                               [:submit {:act "delete-gantt-row" :form {}}])))))
          (is (= :api (ffirst (:fx (ui/handle with-days [:submit {:act "review-gantt-row" :form {:id "1"}}])))))
          (is (= :html (ffirst (:fx (ui/handle (assoc with-days :gantt-selected nil)
                                               [:submit {:act "review-gantt-row" :form {}}])))))
          (is (= :api (ffirst (:fx (ui/handle base [:submit {:act "finalize-gantt-progress"
                                                             :form {:day "2026-09-18" :email "a@b.c"}}])))))
          (is (= :api (ffirst (:fx (ui/handle empty-form [:submit {:act "finalize-gantt-progress"
                                                                   :form {:day "" :email ""}}])))))
          (is (= :api (ffirst (:fx (ui/handle with-days [:gantt-delete-result {:ok true}])))))
          (is (true? (get-in (ui/handle with-days [:gantt-delete-result {:ok false :code "gantt_not_found"}])
                             [:state :flash :error?])))
          (is (map? (ui/handle with-days [:gantt-progress-days-loaded {:ok true}])))
          (is (map? (ui/handle with-days [:gantt-progress-days-loaded {:ok true :days []}])))
          (is (true? (get-in (ui/handle with-days [:gantt-progress-days-loaded {:ok false :code "gantt_not_found"}])
                             [:state :flash :error?])))
          (is (map? (ui/handle base [:gantt-finalize-result {:ok true :finalized 1 :day "2026-09-18"}])))
          (is (true? (get-in (ui/handle base [:gantt-finalize-result {:ok false :code "user_not_found"}])
                             [:state :flash :error?])))
          (let [path-r (ui/handle (assoc base :page :home) [:path {:path "/admin/gantt-progress" :search ""}])]
            (is (= :gantt-progress (get-in path-r [:state :page]))))
          (is (re-find #"削除" (ui/render with-days)))
          (is (re-find #"振り返り" (html {:page :gantt :fields [{:id 1}]
                                          :gantt-rows [{:id 1 :title "行" :start_at "2026-09-18T08:00"
                                                        :end_at "2026-09-18T09:00" :field_ids []}]
                                          :gantt-selected 1
                                          :gantt-progress-days []}))))))))

(deftest p4-spec-ids-present
  (let [doc (slurp (io/file "docs/詳細試験仕様書_工程4.md"))
        ids ["P4-2.1-01" "P4-2.1-02" "P4-2.1-03" "P4-2.1-04" "P4-2.1-05" "P4-2.1-06" "P4-2.1-07"
             "P4-2.2-01" "P4-2.2-02" "P4-2.2-03" "P4-2.2-04" "P4-2.2-05"
             "P4-2.3-01" "P4-2.3-02" "P4-2.3-03" "P4-2.3-04"
             "P4-2.4-01" "P4-2.4-02" "P4-2.4-03" "P4-2.4-04" "P4-2.4-05" "P4-2.4-06"
             "P4-2.4-07" "P4-2.4-08" "P4-2.4-09" "P4-2.4-10" "P4-2.4-11" "P4-2.4-12"
             "P4-2.4-13" "P4-2.4-14" "P4-2.4-15"
             "P4-2.4-c01" "P4-2.4-c02" "P4-2.4-c03" "P4-2.4-c04" "P4-2.4-c05"
             "P4-2.4-c06" "P4-2.4-c07" "P4-2.4-c08" "P4-2.4-c09"
             "P4-3.1-01" "P4-3.1-02" "P4-3.2-01" "P4-3.2-02" "P4-3.2-03" "P4-3.2-04"
             "P4-3.2-05" "P4-3.2-06" "P4-3.2-07" "P4-3.3-01" "P4-3.3-02" "P4-3.3-03"
             "P4-3.3-04" "P4-3.3-05" "P4-3.3-06" "P4-3.3-07" "P4-3.4-01" "P4-3.4-02" "P4-3.5-01"
             "P4-4-01" "P4-4-02" "P4-4-03" "P4-4-04"
             "P4-5-01" "P4-5-02" "P4-5-03" "P4-5-04" "P4-5-05" "P4-5-06" "P4-5-07" "P4-5-08" "P4-5-09"
             "P4-6-01" "P4-6-02" "P4-6-03" "P4-6-04" "P4-6-05" "P4-6-06" "P4-6-07" "P4-6-08" "P4-6-09" "P4-6-10"
             "P4-7-01" "P4-7-02" "P4-7-03" "P4-7-04" "P4-7-05" "P4-7-06" "P4-7-07"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
