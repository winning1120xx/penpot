;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.thumbnail-renderer
  (:require
   [app.common.data.macros :as dm]
   [app.config :as config]
   [app.thumbnail-renderer.promise-map :as promise-map]
   [app.util.dom :as dom]))
   ;; [goog.events :as events]))

(declare send-message)

(defonce promises (promise-map/new))
(defonce ready? (atom false))
(defonce queue (atom #js []))
(defonce instance (atom nil))

(defn get-thumbnail-renderer-url
  "Returns the URL of the thumbnail renderer iframe HTML."
  []
  (dm/str config/thumbnail-renderer-origin "/thumbnail-renderer.html"))

(defn create-iframe
  "Creates an iframe with the given src and appends it to the body."
  [src]
  (let [iframe (dom/create-element "iframe")]
    (dom/set-attribute! iframe "src" src)
    (dom/set-attribute! iframe "hidden" true)
    (dom/append-child! js/document.body iframe)
    iframe))

(defn handle-message
  "Handles a message from the thumbnail renderer."
  [event]
  (when (= config/thumbnail-renderer-origin (.-origin event))
    (let [queue @queue
          data ^js (.-data event)
          type (unchecked-get data "type")
          id (unchecked-get data "id")
          payload (unchecked-get data "payload")]
      (if (= type "ready")
        (do (reset! ready? true)
            (loop [message (.shift queue)]
              (when (some? message)
                (send-message message)
                (recur (.shift queue)))))
        (do
          (when (= type "success")
            (promise-map/fulfill promises id payload))
          (when (= type "failure")
            (promise-map/reject promises id payload)))))))

(defn listen
  "Listens for messages from the thumbnail renderer."
  []
  ;; This isn't working
  ;;(events/listen js/window "message" handle-message))
  (.addEventListener js/window "message" handle-message))

(defn create-id
  "Creates a random ID."
  []
  (.toString (.floor js/Math (* (.random js/Math) (.-MAX_SAFE_INTEGER js/Number))) 36))

(defn send-message
  "Sends a message to the thumbnail renderer."
  [message]
  (let [contentWindow (.-contentWindow @instance)]
    (.postMessage contentWindow message config/thumbnail-renderer-origin)))

(defn queue-message
  "Queues a message to be sent to the thumbnail renderer when it's ready."
  [message]
  (let [queue @queue]
    (.push queue message)))

(defn ask!
  "Sends a message to the thumbnail renderer and returns a promise that will be"
  [type payload]
  (let [id (create-id)
        message #js {:id id
                     :type type
                     :payload payload}
        promise (promise-map/create promises id)
        ready? @ready?]
    (if ready?
      (send-message message)
      (queue-message message))
    promise))

(defn render!
  "Renders a thumbnail."
  [{:keys [data fonts] :as params}]
  (ask! "render" #js {:data data :fonts (clj->js fonts)}))

(defn init!
  "Initializes the thumbnail renderer."
  []
  (let [iframe (create-iframe (get-thumbnail-renderer-url))]
    (listen)
    (reset! instance iframe)))
