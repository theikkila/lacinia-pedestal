(ns com.walmartlabs.lacinia.test-utils
  (:require [clj-http.client :as client]
            [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal :as lp]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [cheshire.core :as cheshire]))

(defn ^:private resolve-echo
  [context args _]
  (let [{:keys [value error]} args
        error-map (when error
                    {:message "Forced error."
                     :status error})
        resolved-value {:value value
                        :method (get-in context [:request :request-method])}]
    (resolve-as resolved-value error-map)))

(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))

(defn ^:private stream-ping
  [context args source-stream]
  (swap! *ping-subscribes inc)
  (let [{:keys [message count]} args
        runnable ^Runnable (fn []
                             (dotimes [i count]
                               (source-stream {:message (str message " #" (inc i))
                                               :timestamp (System/currentTimeMillis)})
                               (Thread/sleep 50))

                             (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))

(defn ^:private make-service
  [options options-builder]
  (let [schema (-> (io/resource "sample-schema.edn")
                   slurp
                   edn/read-string
                   (util/attach-resolvers {:resolve-echo resolve-echo})
                   (util/attach-streamers {:stream-ping stream-ping})
                   schema/compile)
        options' (merge options
                        (options-builder schema))]
    (lp/pedestal-service schema options')))

(defn test-server-fixture
  "Starts up the test server as a fixture."
  ([options]
   (test-server-fixture options (constantly {})))
  ([options options-builder]
   (fn [f]
     (reset! *ping-subscribes 0)
     (reset! *ping-cleanups 0)
     (let [service (make-service options options-builder)]
       (http/start service)
       (try
         (f)
         (finally
           (http/stop service)))))))


(defn get-url
  [path]
  (client/get (str "http://localhost:8888" path) {:throw-exceptions false}))

(defn send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request :get query))
  ([method query]
   (send-request method query nil))
  ([method query vars]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false}
       (cond->
         (= method :get)
         (assoc-in [:query-params :query] query)

         (= method :post)
         (assoc-in [:headers "Content-Type"] "application/graphql")

         ;; :post-bad is like :post, but without setting the content type
         (#{:post :post-bad} method)
         (assoc :body query
                :method :post)

         vars
         (assoc-in [:query-params :variables] (cheshire/generate-string vars)))
       client/request
       (update :body #(try
                        (cheshire/parse-string % true)
                        (catch Exception t
                          %))))))


(defn send-json-request
  ([method json]
   (send-json-request method json "application/json; charset=utf-8"))
  ([method json content-type]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false
        :headers {"Content-Type" content-type}}
       (cond->
         json (assoc :body (cheshire/generate-string json)))
       client/request
       (update :body
               #(try
                  (cheshire/parse-string % true)
                  (catch Exception t %))))))
