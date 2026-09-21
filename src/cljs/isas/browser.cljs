(ns isas.browser
  (:require [clojure.string :as str]
            [isas.ui :as ui]))

(defonce app-state (atom (ui/init-state)))

(defonce map-sync-fn (atom nil))

(defonce gantt-sync-fn (atom nil))

(defonce pending-form-draft (atom nil))

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

(defn- draft-skip-el? [^js el]
  (let [typ (str/lower-case (str (or (.-type el) "")))]
    (or (.getAttribute el "data-select")
        (= typ "file")
        (= typ "submit")
        (= typ "button")
        (= typ "image")
        (= typ "reset"))))

(defn collect-form-draft []
  (let [out (atom {})]
    (try
      (when-let [root (root-el)]
        (when (exists? (.-querySelectorAll root))
          (let [nodes (.querySelectorAll root "input, textarea, select")
                n (.-length nodes)]
            (dotimes [i n]
              (let [^js el (.item nodes i)
                    nm (.getAttribute el "name")]
                (when (and nm (not (draft-skip-el? el)))
                  (let [typ (str/lower-case (str (or (.-type el) "")))
                        k (keyword nm)]
                    (cond
                      (= typ "checkbox")
                      (when (.-checked el)
                        (swap! out update k
                               (fn [prev]
                                 (let [v (.-value el)]
                                   (cond
                                     (nil? prev) [v]
                                     (vector? prev) (conj prev v)
                                     :else [prev v])))))
                      (= typ "radio")
                      (when (.-checked el)
                        (swap! out assoc k (.-value el)))
                      :else
                      (swap! out assoc k (.-value el))))))))))
      (catch :default _
        nil))
    @out))

(defn restore-form-draft! [draft]
  (try
    (when (seq draft)
      (when-let [root (root-el)]
        (when (exists? (.-querySelectorAll root))
          (doseq [[k v] draft]
            (let [nm (name k)
                  nodes (.querySelectorAll root (str "[name=\"" nm "\"]"))
                  n (.-length nodes)]
              (dotimes [i n]
                (let [^js el (.item nodes i)]
                  (when-not (draft-skip-el? el)
                    (let [typ (str/lower-case (str (or (.-type el) "")))]
                      (cond
                        (= typ "checkbox")
                        (let [vals (if (vector? v) v [v])]
                          (set! (.-checked el)
                                (boolean (some #(= (str %) (.-value el)) vals))))
                        (= typ "radio")
                        (set! (.-checked el) (= (str v) (.-value el)))
                        :else
                        (set! (.-value el)
                              (str (if (vector? v) (first v) v)))))))))))))
    (catch :default _
      nil)))

(defn current-path []
  (.-pathname js/location))

(defn current-search []
  (.-search js/location))

(defn queue-form-draft! []
  (reset! pending-form-draft {:path (current-path)
                              :draft (collect-form-draft)}))

(defn take-form-draft! []
  (let [p @pending-form-draft]
    (reset! pending-form-draft nil)
    (when (and p (= (:path p) (current-path)))
      (:draft p))))

(defn push-path! [path]
  (.pushState js/history nil "" path))

(defn guest-lang-key [kind]
  (if (= "admin" (str kind))
    "isas_guest_lang_admin"
    "isas_guest_lang_user"))

(defn read-guest-lang [kind]
  (try
    (let [v (.getItem js/localStorage (guest-lang-key kind))]
      (ui/normalize-lang v))
    (catch :default _
      "ja")))

(defn write-guest-lang! [kind lang]
  (try
    (.setItem js/localStorage (guest-lang-key kind) (ui/normalize-lang lang))
    (catch :default _
      nil)))

(defn kind-from-path [path]
  (:kind (ui/route-for path)))

(defn set-document-lang! [lang]
  (when-let [el (.-documentElement js/document)]
    (set! (.-lang el) (ui/normalize-lang lang))))

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
      :html (let [draft (take-form-draft!)]
              (set-html! a)
              (when (seq draft)
                (restore-form-draft! draft))
              (set-document-lang! (:ui-lang @app-state))
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
      :guest-lang (write-guest-lang! a b)
      :restore-guest-lang
      (swap! app-state assoc :ui-lang (read-guest-lang a))
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
      (let [msg (.getAttribute form "data-confirm")]
        (when (or (str/blank? (str msg)) (js/confirm msg))
          (dispatch! [:submit {:act (.getAttribute form "data-act")
                               :form (form->map form)}]))))))

(defn on-click [ev]
  (let [t (.-target ev)
        a (.closest t "a[data-nav]")]
    (when a
      (.preventDefault ev)
      (reset! pending-form-draft nil)
      (let [href (.getAttribute a "href")]
        (push-path! href)
        (dispatch! [:path {:path href :search ""}])))))

(defn on-change [ev]
  (let [t (.-target ev)
        kind (when t (.getAttribute t "data-select"))
        v (when t (.-value t))]
    (when kind
      (case kind
        "lang" (do (queue-form-draft!)
                   (dispatch! [:set-lang v]))
        "gantt-axis" (do (queue-form-draft!)
                         (dispatch! [:submit {:act "set-gantt-axis" :form {:axis v}}]))
        "gantt-orient" (do (queue-form-draft!)
                           (dispatch! [:submit {:act "set-gantt-orient" :form {:orient v}}]))
        "daily-range" (do (queue-form-draft!)
                          (dispatch! [:submit {:act "set-daily-range" :form {:range v}}]))
        "map-mode" (when-not (str/blank? v)
                     (queue-form-draft!)
                     (dispatch! [:submit {:act "set-map-mode" :form {:mode v}}]))
        "basemap-kind" nil
        nil))))

(defn on-popstate [_ev]
  (dispatch! [:path {:path (current-path) :search (current-search)}]))

(defn on-resize [_ev]
  (dispatch! [:narrow {:narrow? (narrow-screen?)}]))

(defn bind-events! []
  (.addEventListener js/document "submit" on-submit true)
  (.addEventListener js/document "click" on-click)
  (.addEventListener js/document "change" on-change)
  (.addEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "resize" on-resize))

(defn main! []
  (let [path (current-path)
        kind (kind-from-path path)]
    (dispatch! [:boot {:path path
                       :search (current-search)
                       :narrow? (narrow-screen?)
                       :ui-lang (read-guest-lang kind)}]))
  (bind-events!))
