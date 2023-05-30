(ns app.thumbnail-renderer.promise-map)

(defrecord PromiseMap [promises executors])

(defn new
  []
  (PromiseMap. (js/Map.) (js/Map.)))

(defn create
  ([promise-map id time-to-timeout]
   (let [promises (:promises promise-map)
         executors (:executors promise-map)
         promise (js/Promise. 
           (fn [resolve reject]
              (let [timeout 
                    (if (.isFinite js/Number time-to-timeout)
                      (js/setTimeout #(reject (js/Error. "Timeout")) time-to-timeout) 
                      nil)]
                (.set executors id #js [resolve reject timeout]))))]
     (.set promises id promise)
     promise))
  ([promise-map id]
   (create promise-map id nil)))

(defn- retrieve
  [promise-map id]
  (let [promises (:promises promise-map)
        executors (:executors promise-map)
        exists? (.has promises id)
        [resolve, reject, timeout] (.get executors id)]
    (if exists?
      (do
        (when (some? timeout)
          (js/clearTimeout timeout))
        (.delete executors id)
        (.delete promises id)
        [resolve reject])
      nil)))

(defn fulfill
  [promise-map id value]
  (let [[resolve] (retrieve promise-map id)]
    (when (some? resolve)
      (resolve value))))
    
(defn reject
  [promise-map id error]
  (let [[_ reject] (retrieve promise-map id)]
    (when (some? reject)
      (reject error))))
    
(defn cancel
  [promise-map id]
  (let [_ (retrieve promise-map id)] nil))