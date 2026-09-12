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
        doc (clj->js {:getElementById (fn [_] el)
                      :addEventListener (fn [_ _ _])})
        loc (clj->js {:pathname "/admin" :search "?token=z"})
        hist (atom [])
        fetch-calls (atom [])]
    (set! js/document doc)
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
    (b/bind-events!)
    (b/main!)
    (is (map? @b/app-state))))
