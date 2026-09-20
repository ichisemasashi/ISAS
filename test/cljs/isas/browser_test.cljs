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
      (b/on-submit ev))
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
    (let [appended (atom [])]
      (set! js/FormData (fn [] (js-obj "append" (fn [k v] (swap! appended conj [k v])))))
      (set! js/fetch (fn [url opts]
                       (swap! fetch-calls conj [url opts])
                       (js/Promise.resolve (clj->js {:text (fn [] (js/Promise.resolve "{\"ok\":true}"))}))))
      (b/apply-fx! [:upload "PUT" "/api/user/basemaps/aerial" {:kind "aerial" :file "x" :skip nil} :basemap-upload-result])
      (is (= [["kind" "aerial"] ["file" "x"]] @appended))
      (set! js/fetch (fn [_ _] (js/Promise.reject (js/Error. "net"))))
      (b/apply-fx! [:upload "POST" "/api/user/fields/import" {:file "y"} :field-save-result]))
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
    (is (= "user" (b/kind-from-path "/home")))
    (is (= "admin" (b/kind-from-path "/admin/home")))
    (b/bind-events!)
    (b/main!)
    (is (map? @b/app-state))))
