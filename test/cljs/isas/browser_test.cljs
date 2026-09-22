(ns isas.browser-test
  (:require [cljs.test :refer [deftest is]]
            [isas.browser :as b]
            [isas.ui :as ui]))

(defn fake-el []
  (let [html (atom "")]
    (js-obj "innerHTML" ""
            "get innerHTML" (fn [] @html)
            "-innerHTML" html)))

(deftest browser-helpers-test
  (let [html (atom nil)
        el (clj->js {:innerHTML ""})
        doc-el (clj->js {:lang "ja"})
        store (atom {})
        doc (clj->js {:getElementById (fn [_] el)
                      :documentElement doc-el
                      :addEventListener (fn [_ _ _])})
        loc (clj->js {:pathname "/admin" :search "?token=z"})
        hist (atom [])
        fetch-calls (atom [])]
    (set! js/document doc)
    (set! js/localStorage (clj->js {:getItem (fn [k] (get @store k))
                                    :setItem (fn [k v] (swap! store assoc k v))}))
    (set! js/location loc)
    (set! js/history (clj->js {:pushState (fn [_ _ path] (swap! hist conj path))}))
    (set! js/window (clj->js {:addEventListener (fn [_ _])}))
    (set! (.-innerHTML el) "old")
    (is (= "/admin" (b/current-path)))
    (is (= "?token=z" (b/current-search)))
    (b/set-html! "<p>x</p>")
    (is (= "<p>x</p>" (.-innerHTML el)))
    (set! js/document (clj->js {:getElementById (fn [_] nil)}))
    (b/set-html! "nope")
    (set! js/document doc)
    (b/push-path! "/home")
    (is (= ["/home"] @hist))
    (is (= "/x" (b/api-url "/x")))
    (is (= {:ok true} (b/parse-json "{\"ok\":true}")))
    (is (= "unauthorized" (:code (b/parse-json "not-json"))))
    (set! js/FormData (fn [_]
                        (js-obj "forEach" (fn [f] (f "a@b.c" "email")))))
    (is (= {:email "a@b.c"} (b/form->map #js {})))
    (reset! b/app-state (ui/init-state))
    (b/apply-fx! [:html "<b>1</b>"])
    (is (= "<b>1</b>" (.-innerHTML el)))
    (set! js/fetch (fn [url opts]
                     (swap! fetch-calls conj [url opts])
                     (js/Promise.resolve (clj->js {:text (fn [] (js/Promise.resolve "{\"ok\":true,\"email\":\"a@b.c\"}"))}))))
    (b/apply-fx! [:session "user"])
    (b/apply-fx! [:session "admin"])
    (b/apply-fx! [:api "POST" "/api/user/login" {:email "a"} :login-result])
    (b/apply-fx! [:nav "/home"])
    (b/apply-fx! [:unknown])
    (set! js/fetch (fn [_ _]
                     (js/Promise.reject (js/Error. "net"))))
    (b/apply-fx! [:api "GET" "/api/user/session" nil :session-loaded])
    (let [form (clj->js {:getAttribute (fn [k] (when (= k "data-act") "login"))})
          ev (clj->js {:target form :preventDefault (fn [])})]
      (set! js/FormData (fn [_] (js-obj "forEach" (fn [f] (f "x" "email")))))
      (set! js/confirm (fn [_] true))
      (b/on-submit ev))
    (let [form (clj->js {:getAttribute (fn [k]
                                         (case k
                                           "data-act" "delete-gantt-row"
                                           "data-confirm" "ok?"
                                           nil))})
          ev (clj->js {:target form :preventDefault (fn [])})
          asked (atom nil)]
      (set! js/confirm (fn [m] (reset! asked m) false))
      (b/on-submit ev)
      (is (= "ok?" @asked)))
    (let [form (clj->js {:getAttribute (fn [_] nil)})
          ev (clj->js {:target form :preventDefault (fn [])})]
      (b/on-submit ev))
    (b/on-submit (clj->js {:target nil :preventDefault (fn [])}))
    (let [a (clj->js {:getAttribute (fn [_] "/invite")})
          t (clj->js {:closest (fn [_] a)})
          ev (clj->js {:target t :preventDefault (fn [])})]
      (b/on-click ev))
    (let [t (clj->js {:closest (fn [_] nil)})
          ev (clj->js {:target t :preventDefault (fn [])})]
      (b/on-click ev))
    (b/on-popstate nil)
    (b/on-resize nil)
    (set! js/window (clj->js {:innerWidth 500 :addEventListener (fn [_ _])}))
    (is (true? (b/narrow-screen?)))
    (set! js/window (clj->js {:innerWidth 1200 :addEventListener (fn [_ _])}))
    (is (false? (b/narrow-screen?)))
    (set! js/window (clj->js {:innerWidth nil :addEventListener (fn [_ _])}))
    (is (false? (b/narrow-screen?)))
    (let [synced (atom nil)]
      (b/register-map-sync! (fn [st _] (reset! synced st)))
      (reset! b/app-state (ui/init-state))
      (b/apply-fx! [:html "<div id=\"ol-map\"></div>"])
      (is (some? @synced))
      (b/register-map-sync! nil)
      (b/apply-fx! [:html "<p>2</p>"]))
    (let [appended (atom [])
          store (atom {})]
      (set! js/FormData (fn []
                          (js-obj "append" (fn [k v] (swap! store assoc k v)
                                                   (swap! appended conj [k v]))
                                  "has" (fn [k] (contains? @store k))
                                  "delete" (fn [k] (swap! store dissoc k)))))
      (set! js/fetch (fn [url opts]
                       (swap! fetch-calls conj [url opts])
                       (js/Promise.resolve (clj->js {:text (fn [] (js/Promise.resolve "{\"ok\":true}"))}))))
      (b/apply-fx! [:upload "PUT" "/api/user/basemaps/aerial" {:kind "aerial" :file "x" :skip nil} :basemap-upload-result])
      (is (= [["kind" "aerial"] ["file" "x"]] @appended))
      (set! js/fetch (fn [_ _] (js/Promise.reject (js/Error. "net"))))
      (b/apply-fx! [:upload "POST" "/api/user/fields/import" {:file "y"} :field-save-result])
      (b/apply-fx! [:upload-status "アップロード中…"]))
    (b/on-change (clj->js {:target (clj->js {:type "file"
                                             :getAttribute (fn [a]
                                                             (case a
                                                               "data-auto-upload" "1"
                                                               "data-select" nil
                                                               nil))
                                             :files #js [#js {:name "a.txt"}]
                                             :closest (fn [_]
                                                        (clj->js {:getAttribute
                                                                  (fn [a]
                                                                    (when (= a "data-act") "memo-attach"))
                                                                  :requestSubmit (fn [])}))
                                             :value "x"})}))
    (b/on-change (clj->js {:target (clj->js {:type "text"
                                             :getAttribute (fn [_] nil)
                                             :value "x"})}))
    (b/apply-fx! [:guest-lang "user" "en"])
    (is (= "en" (b/read-guest-lang "user")))
    (b/apply-fx! [:guest-lang "admin" "ja"])
    (is (= "ja" (b/read-guest-lang "admin")))
    (reset! b/app-state (assoc (ui/init-state) :ui-lang "en"))
    (b/apply-fx! [:html "<p>en</p>"])
    (is (= "en" (.-lang (.-documentElement js/document))))
    (b/apply-fx! [:restore-guest-lang "user"])
    (is (= "en" (:ui-lang @b/app-state)))
    (let [sel (clj->js {:getAttribute (fn [a] (when (= a "data-select") "lang"))
                        :value "en"})
          ev (clj->js {:target sel})]
      (reset! b/app-state (assoc (ui/init-state) :session nil :kind "user" :ui-lang "ja" :page :login))
      (b/on-change ev)
      (is (= "en" (:ui-lang @b/app-state))))
    (let [sel (clj->js {:getAttribute (fn [a] (when (= a "data-select") "gantt-axis"))
                        :value "week"})
          ev (clj->js {:target sel})]
      (reset! b/app-state (assoc (ui/init-state) :page :gantt :gantt-axis "day"
                                 :fields [{:id 1}] :gantt-rows []))
      (b/on-change ev)
      (is (= "week" (:gantt-axis @b/app-state))))
    (let [sel (clj->js {:getAttribute (fn [a] (when (= a "data-select") "gantt-orient"))
                        :value "time-v"})
          ev (clj->js {:target sel})]
      (reset! b/app-state (assoc (ui/init-state) :page :gantt :gantt-orient "time-h"
                                 :fields [{:id 1}] :gantt-rows []))
      (b/on-change ev)
      (is (= "time-v" (:gantt-orient @b/app-state))))
    (let [sel (clj->js {:getAttribute (fn [a] (when (= a "data-select") "map-mode"))
                        :value "paint"})
          ev (clj->js {:target sel})]
      (reset! b/app-state (assoc (ui/init-state) :page :map :map-mode "browse"
                                 :place {:west 1 :south 2 :east 3 :north 4}))
      (b/on-change ev)
      (is (= "paint" (:map-mode @b/app-state))))
    (b/on-change (clj->js {:target (clj->js {:getAttribute (fn [_] "map-mode") :value ""})}))
    (b/on-change (clj->js {:target (clj->js {:getAttribute (fn [_] "basemap-kind") :value "aerial"})}))
    (b/on-change (clj->js {:target (clj->js {:getAttribute (fn [_] nil) :value "x"})}))
    ;; P6-2.1-08 / B6-4.8-07: 切替で HTML を描き直しても未送信入力を残す
    (let [email-el (js-obj "tagName" "INPUT"
                           "type" "text"
                           "name" "email"
                           "value" "typed@example.com"
                           "checked" false
                           "getAttribute" (fn [a] (when (= a "name") "email")))
          lang-el (js-obj "tagName" "SELECT"
                          "type" "select-one"
                          "name" nil
                          "value" "en"
                          "checked" false
                          "getAttribute" (fn [a] (when (= a "data-select") "lang")))
          cb1 (js-obj "tagName" "INPUT"
                      "type" "checkbox"
                      "name" "field_ids"
                      "value" "1"
                      "checked" true
                      "getAttribute" (fn [a] (when (= a "name") "field_ids")))
          cb2 (js-obj "tagName" "INPUT"
                      "type" "checkbox"
                      "name" "field_ids"
                      "value" "2"
                      "checked" true
                      "getAttribute" (fn [a] (when (= a "name") "field_ids")))
          radio-el (js-obj "tagName" "INPUT"
                           "type" "radio"
                           "name" "kind"
                           "value" "aerial"
                           "checked" true
                           "getAttribute" (fn [a] (when (= a "name") "kind")))
          file-el (js-obj "tagName" "INPUT"
                          "type" "file"
                          "name" "file"
                          "value" "x"
                          "checked" false
                          "getAttribute" (fn [a] (when (= a "name") "file")))
          nodes-atom (atom #js [email-el lang-el cb1 cb2 radio-el file-el])
          root (js-obj "querySelectorAll"
                       (fn [sel]
                         (let [arr (if (re-find #"^input" (str sel))
                                     @nodes-atom
                                     (clj->js
                                      (filterv (fn [el]
                                                 (= (second (re-find #"name=\"([^\"]+)\"" (str sel)))
                                                    (.getAttribute el "name")))
                                               (array-seq @nodes-atom))))]
                           (js-obj "length" (.-length arr)
                                   "item" (fn [i] (aget arr i)))))
                       "innerHTML" "")]
      (set! js/document (clj->js {:getElementById (fn [_] root)
                                  :documentElement doc-el
                                  :addEventListener (fn [_ _ _])}))
      (let [draft (b/collect-form-draft)]
        (is (= "typed@example.com" (:email draft)))
        (is (= ["1" "2"] (:field_ids draft)))
        (is (= "aerial" (:kind draft)))
        (is (nil? (:file draft))))
      (set! (.-value email-el) "")
      (set! (.-checked cb1) false)
      (set! (.-checked cb2) false)
      (set! (.-checked radio-el) false)
      (b/restore-form-draft! {:email "typed@example.com"
                              :field_ids ["1" "2"]
                              :kind "aerial"})
      (is (= "typed@example.com" (.-value email-el)))
      (is (true? (.-checked cb1)))
      (is (true? (.-checked cb2)))
      (is (true? (.-checked radio-el)))
      (set! (.-value email-el) "keep-me")
      (reset! b/pending-form-draft nil)
      (set! js/location (clj->js {:pathname "/login" :search ""}))
      (b/queue-form-draft!)
      (is (= "/login" (:path @b/pending-form-draft)))
      (reset! b/app-state (assoc (ui/init-state) :ui-lang "ja"))
      (b/apply-fx! [:html "<p>switched</p>"])
      (is (= "keep-me" (.-value email-el)))
      ;; 別画面へ遷移したあとの描き直しには draft を載せない
      (set! (.-value email-el) "from-old-page")
      (b/queue-form-draft!)
      (set! js/location (clj->js {:pathname "/orders/new" :search ""}))
      (b/apply-fx! [:html "<p>nav</p>"])
      (is (= "from-old-page" (.-value email-el)))
      (is (nil? @b/pending-form-draft))
      ;; 切替以外の描き直しでは draft を復元しない
      (set! (.-value email-el) "nav-value")
      (b/apply-fx! [:html "<p>nav2</p>"])
      (is (= "nav-value" (.-value email-el)))
      (b/restore-form-draft! {:email ["vector-email"] :field_ids "1"})
      (is (= "vector-email" (.-value email-el)))
      (is (true? (.-checked cb1)))
      (set! js/location loc)
      (reset! nodes-atom #js [])
      (is (= {} (b/collect-form-draft)))
      (b/restore-form-draft! {})
      (b/restore-form-draft! nil)
      (set! js/document (clj->js {:getElementById (fn [_] nil)
                                  :documentElement doc-el}))
      (is (= {} (b/collect-form-draft)))
      (b/restore-form-draft! {:email "x"})
      (set! js/document doc))
    (is (= "user" (b/kind-from-path "/home")))
    (is (= "admin" (b/kind-from-path "/admin/home")))
    (b/bind-events!)
    (b/main!)
    (is (map? @b/app-state))))
