(ns nrepl.server
  (:require [net :as net]
            [System]
            [cljs.tools.reader :refer [read-string]]
            [cljs.js :as cljs]
            [lumo.repl :as repl]
            [clojure.string :as s]
            [nrepl.bencode :refer [encode decode-all]]
            [clojure.pprint :refer [pprint]]))

(defonce sessions (atom {}))

(defn ignore [source]
  (some #(s/includes? source %)
        ["cljs.repl.nashorn"
         "cider.piggieback"
         "cemerick.piggieback"
         "cljs.repl.rhino"]))

(defn lumo-eval [source ns session]
  (when-not (ignore source)
    (let [value (atom nil)
          error (atom nil)
          source (s/replace source #"cljs\.repl\/source" "lumo.repl/source")
          f js/$$LUMO_GLOBALS.doPrint
          handle-error lumo.repl/handle-error]
      (set! js/$$LUMO_GLOBALS.doPrint #())

    ; small work around to work around a bug in lumo
      (repl/execute "text" (str "(in-ns '" ns ")") true false ns 0)

    ; capture execution results
      (set! js/$$LUMO_GLOBALS.doPrint #(reset! value %2))
      (set! lumo.repl/handle-error #(reset! error %))

      (repl/execute "text" source true false ns 0)

    ; reset internals
      (set! js/$$LUMO_GLOBALS.doPrint f)
      (set! lumo.repl/handle-error handle-error)

      (if @error (throw @error) @value))))

(defn init []
  (let [vars
        '{cljs.core/all-ns lumo.repl/all-ns
          cljs.core/ns-map lumo.repl/ns-map
          cljs.core/ns-aliases lumo.repl/ns-map
          clojure.repl/special-doc lumo.repl/special-doc
          clojure.repl/namespace-doc lumo.repl/namespace-doc}]
    (->> vars
         (map
          (fn [[k v]]
            (lumo-eval
             (str (list 'def (-> k name symbol) v))
             (-> k namespace)
             0)))
         dorun)
    (lumo-eval "(require 'lumo.repl)" "cljs.user" "")))

(defn dispatch [req]
  (case (:op req)
    :clone
    (let [new-session (str (random-uuid))]
      (swap! sessions
             assoc
             new-session
             {:compiler (cljs.js/empty-state)})
      {:new-session new-session})
    :describe
    {:ops {:stacktrace 1}
     :versions
     {"nrepl"
      {"major" 0 "minor" 2}}}
    :stacktrace
    {:name ""
     :class ""
     :method "test"
     :message ""}
    :interrupt {}
    :eval
    (let [code (:code req)
          session (:session req)
          ns (or (:ns req) "cljs.user")]
      (try
        (let [value (atom nil)
              out (with-out-str
                    (reset! value (lumo-eval code ns session)))
              res {:ns ns :value @value}]
          (if (empty? out) res (assoc res :out out)))
        (catch js/Object e
          (set! *e e)
          (loop [e (ex-cause e)]
            (cond
              (nil? e) {:err "unknown"}
              (ex-cause e)
              {:err (.-stack (ex-cause e)) :ex (pr-str e)}
              :else (recur (ex-cause e)))))))
    :load-file {}
    :close {}))

(defn dispatch-send [req send]
  (let [op (-> req :op keyword)
        res (dispatch (assoc req :op op))]
    (if (= op :clone)
      (send (assoc res :status [:done]))
      (do
        (.then (js/Promise.resolve (send res))
               #(send {:status ["done"]}))))))

(defn promise? [v] (instance? js/Promise v))

(defn stringify-value [handler]
  (fn [req send]
    (handler req
             (fn [res]
               (if (contains? res :value)
                 (let [value (:value res)]
                   (if (promise? value)
                     (-> value
                         (.then #(str "#object[Promise " (pr-str %) "]"))
                         (.catch #(str "#object[Promise " (pr-str %) "]"))
                         (.then #(send (assoc res :value %))))
                     (send (assoc res :value (pr-str value)))))
                 (send res))))))

(defn attach-id [handler]
  (fn [req send]
    (let [{:keys [id session]} req]
      (handler req #(send (merge {:id id} (when session {:session session}) %))))))

(defn logger [handler]
  (fn [req send]
    (pprint (assoc req :type :request))
    (handler req #(do (pprint (assoc % :type :response)) (send %)))))

(defn transport [socket state data]
  (let [data (if (nil? state)
               data
               (js/Buffer.concat (clj->js [state data])))
        [reqs data] (decode-all data :keywordize-keys true)
        handler (-> dispatch-send
                    attach-id
                    stringify-value
                    #_logger)]
    (doseq [req reqs]
      (handler req #(do
                      (.write socket (encode %))
                      (when (and (= (:op req) "close")
                                 (contains? % :status))
                        (.end socket)))))
    data))

(defn setup [socket]
  (let [state (atom nil)]
    (.setNoDelay socket true)
    (.on socket "data" #(reset! state (transport socket @state %)))))

(defn start-server []
  (init)
  (js/Promise.
   (fn [resolve reject]
     (let [srv (net/createServer setup)]
       (.on srv "error" reject)
       (.listen
        srv
        7888
        (fn []
          (resolve {:handle srv :port (.-port (.address srv))})))))))

(defn stop-server [server]
  (.close (:handle server)))

(defn -main [] (start-server))

