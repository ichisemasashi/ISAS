(ns isas.browser
  (:require [clojure.string :as str]
            [isas.ui :as ui]))

(defonce app-state (atom (ui/init-state)))

(defonce map-sync-fn (atom nil))

(defonce gantt-sync-fn (atom nil))

(defn register-map-sync! [f]
  (reset! map-sync-fn f))

(defn register-gantt-sync! [f]
  (reset! gantt-sync-fn f))

(defn narrow-screen? []
  (let [w (.-innerWidth js/window)]
    (boolean (and w (< w 800)))))

(defn root-el []
  (.getElementById js/document "app"))

(defn set-html! [html]
  (when-let [el (root-el)]
    (set! (.-innerHTML el) html)))

(defn current-path []
  (.-pathname js/location))

(defn current-search []
  (.-search js/location))

(defn push-path! [path]
  (.pushState js/history nil "" path))

(defn form->map [form]
  (let [fd (js/FormData. form)
        out (atom {})]
    (.forEach fd (fn [v k]
                   (let [key (keyword k)]
                     (swap! out update key
                            (fn [prev]
                              (cond
                                (nil? prev) v
                                (vector? prev) (conj prev v)
                                :else [prev v]))))))
    @out))

(defn parse-json [text]
  (try
    (js->clj (js/JSON.parse text) :keywordize-keys true)
    (catch :default _
      {:ok false :code "unauthorized"})))

(defn api-url [path] path)

(defn fetch-api [method path body cb]
  (-> (js/fetch (api-url path)
                (clj->js (cond-> {:method method
                                  :credentials "same-origin"
                                  :headers {}}
                           body (assoc :headers {:content-type "application/json"}
                                       :body (js/JSON.stringify (clj->js body))))))
      (.then (fn [res] (.text res)))
      (.then (fn [text] (cb (parse-json text))))
      (.catch (fn [_] (cb {:ok false :error true})))))

(defn fetch-upload [method path form cb]
  (let [fd (js/FormData.)]
    (doseq [[k v] form]
      (when (and k v)
        (.append fd (name k) v)))
    (-> (js/fetch (api-url path)
                  (clj->js {:method method
                            :credentials "same-origin"
                            :body fd}))
        (.then (fn [res] (.text res)))
        (.then (fn [text] (cb (parse-json text))))
        (.catch (fn [_] (cb {:ok false :error true}))))))

(declare dispatch!)

(defn apply-fx! [fx]
  (let [[op a b c d] fx]
    (case op
      :html (do (set-html! a)
                (when-let [f @map-sync-fn]
                  (f @app-state dispatch!))
                (when-let [f @gantt-sync-fn]
                  (f @app-state dispatch!)))
      :nav (do (push-path! a)
               (dispatch! [:path {:path a :search ""}]))
      :session (fetch-api "GET"
                          (if (= a "admin") "/api/admin/session" "/api/user/session")
                          nil
                          (fn [body] (dispatch! [:session-loaded body])))
      :api (fetch-api a b c (fn [body]
                              (if (:error body)
                                (dispatch! [:api-error])
                                (dispatch! [d body]))))
      :upload (fetch-upload a b c (fn [body]
                                    (if (:error body)
                                      (dispatch! [:api-error])
                                      (dispatch! [d body]))))
      nil)))

(defn dispatch! [msg]
  (let [{:keys [state fx]} (ui/handle @app-state msg)]
    (reset! app-state state)
    (doseq [f fx]
      (apply-fx! f))))

(defn on-submit [ev]
  (when-let [form (.-target ev)]
    (when (.getAttribute form "data-act")
      (.preventDefault ev)
      (dispatch! [:submit {:act (.getAttribute form "data-act")
                           :form (form->map form)}]))))

(defn on-click [ev]
  (let [t (.-target ev)
        a (.closest t "a[data-nav]")]
    (when a
      (.preventDefault ev)
      (let [href (.getAttribute a "href")]
        (push-path! href)
        (dispatch! [:path {:path href :search ""}])))))

(defn on-popstate [_ev]
  (dispatch! [:path {:path (current-path) :search (current-search)}]))

(defn on-resize [_ev]
  (dispatch! [:narrow {:narrow? (narrow-screen?)}]))

(defn bind-events! []
  (.addEventListener js/document "submit" on-submit true)
  (.addEventListener js/document "click" on-click)
  (.addEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "resize" on-resize))

(defn main! []
  (dispatch! [:boot {:path (current-path) :search (current-search) :narrow? (narrow-screen?)}])
  (bind-events!))
