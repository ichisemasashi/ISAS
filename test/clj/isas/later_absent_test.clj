(ns isas.later-absent-test
  "工程5〜6は未着手。基本・詳細試験のうち「まだ無い／出さない」項だけを自動試験する。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(def later-apis
  [["P5" ["/api/user/orders" "/api/user/journals" "/api/admin/relations/cut"]]
   ["P6" ["/api/user/locale" "/api/admin/locale"]]])

(deftest later-phase-features-are-absent
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            names (tu/table-names (:ds sys))
            map-html (html {:page :map :place {:west 1 :south 2 :east 3 :north 4}})
            home (html {:page :home})
            admin-home (html {:page :home :kind "admin"})]
        (testing "B3-2-04 / P3-2.1-08 指示は地図に出さない。ガント表はある"
          (is (not (re-find #"指示" map-html)))
          (is (not (re-find #"ガント" map-html)))
          (is (contains? names "paints"))
          (is (contains? names "gantt_rows")))
        (testing "B3-2-03 / B3-5.4-01 狭い画面に塗りは持たない"
          (is (not (re-find #"手描き|全面完了|ブラシ" (html {:page :map :narrow? true})))))
        (testing "B3-3-02 他人の圃場は見ない"
          (is (nil? (http/match-api :get "/api/user/others/fields"))))
        (testing "P4 ガントはある。行 DELETE と旧 percent 経路は無い"
          (is (= [:gantt-get] (http/match-api :get "/api/user/gantt")))
          (is (nil? (http/match-api :delete "/api/user/gantt/1")))
          (is (nil? (http/match-api :get "/api/user/gantt/percent")))
          (is (= :gantt (:page (ui/route-for "/gantt"))))
          (is (not (re-find #"href=\"/gantt\"" home))))
        (testing "B5-2-01 / P5 指示・日誌・切断は無い"
          (is (nil? (http/match-api :post "/api/user/orders")))
          (is (nil? (http/match-api :post "/api/admin/relations/cut")))
          (is (not (contains? names "orders")))
          (is (not (contains? names "journals")))
          (is (not (re-find #"関係を切" admin-home))))
        (testing "B6-2-01 / P6 言語切替の本機能は無い"
          (is (nil? (http/match-api :put "/api/user/locale")))
          (is (not (re-find #"English|言語切替" (html {:page :login :kind "user" :session nil})))))
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
          (is (= :unknown (:page (ui/route-for "/orders"))))
          (is (= :unknown (:page (ui/route-for "/en")))))))))
