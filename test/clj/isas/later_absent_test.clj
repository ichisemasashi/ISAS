(ns isas.later-absent-test
  "第1版以降の対象外・誤経路が混入していないことを自動試験する。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(def later-apis
  [["wrong-lang-path" ["/api/user/locale" "/api/admin/locale"]]])

(deftest later-phase-features-are-absent
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            names (tu/table-names (:ds sys))
            map-html (html {:page :map :place {:west 1 :south 2 :east 3 :north 4}})
            home (html {:page :home})
            admin-home (html {:page :home :kind "admin"})]
        (testing "B3-2-04 / P3-2.1-08 指示編集は地図に出さない。ガント表はある"
          (is (not (re-find #"出した指示|受けた指示|この指示を閉じる" map-html)))
          (is (not (re-find #"id=\"gantt-titles\"|id=\"gantt-axis\"" map-html)))
          (is (contains? names "paints"))
          (is (contains? names "gantt_rows")))
        (testing "B3-2-03 / B3-5.4-01 狭い画面に塗りは持たない"
          (is (not (re-find #"手描き|全面完了|ブラシ" (html {:page :map :narrow? true})))))
        (testing "P4 ガントはある。旧 percent 経路は無い。行はソフト削除"
          (is (= [:gantt-get] (http/match-api :get "/api/user/gantt")))
          (is (= [:gantt-delete "1"] (http/match-api :delete "/api/user/gantt/1")))
          (is (nil? (http/match-api :get "/api/user/gantt/percent")))
          (is (= :gantt (:page (ui/route-for "/gantt"))))
          (is (re-find #"href=\"/gantt\"" home)))
        (testing "P5 指示・日誌・切断はある"
          (is (= [:orders-post] (http/match-api :post "/api/user/orders")))
          (is (= [:relations-cut] (http/match-api :post "/api/admin/relations/cut")))
          (is (contains? names "orders"))
          (is (contains? names "journals"))
          (is (re-find #"関係を切" admin-home)))
        (testing "言語は /api/*/language。旧 locale と専用 URL は無い"
          (is (some? (http/match-api :put "/api/user/language")))
          (is (some? (http/match-api :put "/api/admin/language")))
          (is (nil? (http/match-api :put "/api/user/locale")))
          (is (= :unknown (:page (ui/route-for "/en"))))
          (is (re-find #"data-select=\"lang\"" (html {:page :login :kind "user" :session nil}))))
        (doseq [[phase paths] later-apis]
          (testing (str phase " の本機能 API はまだ無い")
            (doseq [p paths]
              (is (nil? (http/match-api :get p)))
              (is (nil? (http/match-api :post p)))
              (is (nil? (http/match-api :put p))))))
        (testing "B3-8 / B6-8 圃場編集地図に地理院ライブ URL を HTML で埋め込まない"
          (is (not (re-find #"cyberjapandata|tile.openstreetmap" map-html))))
        (testing "P1-7-02 と共通の対象外"
          (is (= 401 (:status (tu/get-path app "/api/user/paints"))))
          (is (= :orders (:page (ui/route-for "/orders"))))
          (is (= :unknown (:page (ui/route-for "/en")))))))))
