;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.thumbnail-renderer
  (:require 
   [app.common.data.macros :as dm]
   [app.config :as config]
   [app.main.fonts :as fonts]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   ;; [goog.events :as events]
   [beicon.core :as rx]
   [promesa.core :as p]))

(declare send-success)
(declare send-failure)
(declare send-message)

(defn- create-image
  "Creates an image element with the given URI."
  [uri]
  (p/create
    (fn [resolve reject]
      (let [image (js/Image.)]
        (obj/set! image "onload" #(resolve image))
        (obj/set! image "onerror" #(reject %))
        (obj/set! image "onabort" #(reject (js/Error. "Abort")))
        (obj/set! image "crossOrigin" "anonymous")
        (obj/set! image "src" uri)))))

(defn- svg-has-intrinsic-size
  "Returns true if the SVG has an intrinsic size."
  [svg]
  (let [documentElement (.-documentElement svg)
        width (.getAttribute documentElement "width")
        height (.getAttribute documentElement "height")]
    (and (not (nil? width))
         (not (nil? height)))))

(defn- svg-get-viewbox
  "Returns the viewbox of an SVG as a vector of numbers."
  [svg]
  (let [documentElement (.-documentElement svg)
        viewbox (.getAttribute documentElement "viewBox")]
    (when-not (nil? viewbox)
      (map #(js/parseInt % 10) (.split viewbox " ")))))

(defn- get-proportional-size
  "Returns the proportional size of a rectangle given a max size."
  [width height max]
  (let [aspect-ratio (/ width height)]
    (if (> width height)
      [max (* max (/ 1 aspect-ratio))]
      [(* max aspect-ratio) max])))

(defn- svg-set-intrinsic-size
  "Sets the intrinsic size of an SVG to the given max size."
  [svg max]
  (when-not (svg-has-intrinsic-size svg)
    (let [documentElement (.-documentElement svg)
          [_ _ width height] (svg-get-viewbox svg)
          [width height] (get-proportional-size width height max)]
      (.setAttribute documentElement "width" (str width))
      (.setAttribute documentElement "height" (str height))))
  svg)

(defn- read-as-data-url
  "Reads a blob as a Data URI."
  [blob]
  (p/create
    (fn [resolve reject]
      (let [reader (js/FileReader.)]
        (obj/set! reader "onload" #(resolve (.-result reader)))
        (obj/set! reader "onerror" #(reject %))
        (obj/set! reader "onabort" #(reject (js/Error. "Abort")))
        (.readAsDataURL reader blob)))))

(defn- fetch-as-data-url
  "Fetches a URL as a Data URI."
  [url]
  (-> (js/fetch url)
      (p/then #(.blob %))
      (p/then #(read-as-data-url %))
      (p/then #(dm/str "url(" % ")"))))

(defn- svg-resolve-image-as-data-uri
  "Resolves an image URI to a Data URI."
  [uri]
  (-> (p/then (js/fetch uri) #(.blob %))
      (p/then #(read-as-data-url %))))

(defn- svg-get-images
  "Returns all images in an SVG."
  [svg]
  (let [images (.from js/Array (dom/query-all svg "image"))] images))

(defn- svg-update-image
  "Updates an image in an SVG to a Data URI."
  [image]
  (let [href (.getAttribute image "href")]
    (when-not (nil? href)
      (p/then (svg-resolve-image-as-data-uri href) #(.setAttribute image "href" %)))))

(defn- svg-update-images
  "Updates all images in an SVG to Data URIs."
  [images]
  (.map (fn [image] (svg-update-image image)) images))

(defn- svg-resolve-images
  "Resolves all images in an SVG to Data URIs."
  [svg]
  (let [images (svg-get-images svg)]
    (if (= 0 (.-length images))
      (p/resolved svg)
      (-> (p/all (svg-update-images images))
          (p/then #(svg))))))

(defn- svg-add-style
  "Adds a <style> node to an SVG."
  [svg css]
  (let [documentElement (.-documentElement svg)
        style (.createElementNS svg "http://www.w3.org/2000/svg" "style")
        cssText (.createTextNode svg css)]
    (.appendChild style cssText)
    (.appendChild documentElement style)
    svg))

(defn- svg-add-styles
  "Adds multiple <style> nodes to an SVG."
  [svg styles]
  (mapv #(svg-add-style svg %) styles)
  svg)

(defn- replace-match-async
  "Replaces a regex match asynchronously"
  [match str regex predicate]
  (-> (.apply predicate nil match)
      (p/then (fn [newStr]
                (let [replacedStr (dm/str (.slice str 0 (.-index match)) newStr (.slice str (+ (.-index match) (.-length (first match)))))
                      nextMatch (.match replacedStr regex)]
                  (if (nil? nextMatch)
                    (p/resolved replacedStr)
                    (replace-match-async nextMatch replacedStr regex predicate)))))))

(defn- replace-async
  "Replaces a regex with an async predicate function."
  [str regex predicate]
  (let [match (.match str regex)]
    (if (nil? match)
      (p/resolved str)
      (replace-match-async match str regex predicate))))

(defn- replace-fontface-urls
  "Parses the CSS and retrieves the font urls"
  [css]
  (replace-async css #"url\((https?://[^)]+)\)" (fn [_ url] (fetch-as-data-url url))))

(defn- font-load
  "Returns a map of font names to font data."
  [id]
  (p/create (fn [resolve]
              (->> (rx/of {:font-id id})
                   (rx/flat-map fonts/fetch-font-css)
                   (rx/flat-map replace-fontface-urls)
                   (rx/reduce (fn [acc css] (dm/str acc css)) "")
                   (rx/subs (fn [data] (resolve data)))))))

(defn- fonts-load
  "Loads a list of fonts."
  [fonts]
  (p/all (.map fonts #(font-load %))))

(defn- svg-resolve-fonts
  "Resolves all fonts in an SVG to Data URIs."
  [svg fonts]
  (-> (fonts-load fonts)
      (p/then #(svg-add-styles svg %))))

(defn- svg-resolve-all
  "Resolves all images and fonts in an SVG to Data URIs."
  [svg fonts]
  (-> (svg-resolve-images svg)
      (p/then #(svg-resolve-fonts % fonts))))

(defn- svg-parse
  "Parses an SVG string into an SVG DOM."
  [data]
  (let [parser (js/DOMParser.)]
    (.parseFromString parser data "image/svg+xml")))

(defn- svg-stringify
  "Converts an SVG to a string."
  [svg]
  (let [documentElement (.-documentElement svg)
        serializer (js/XMLSerializer.)]
    (.serializeToString serializer documentElement)))

(defn- svg-prepare
  "Prepares an SVG for rendering (resolves images to Data URIs and adds intrinsic size)."
  [svg fonts]
  (p/then (svg-resolve-all (svg-parse svg) fonts) #(svg-stringify (svg-set-intrinsic-size % 300))))

(defn- image-bitmap-to-blob
  "Converts an ImageBitmap to a Blob."
  [image-bitmap]
  (let [promise (p/create
    (fn [resolve] 
      (let [canvas (dom/create-element "canvas")]
        (set! (.-width canvas) (.-width image-bitmap))
        (set! (.-height canvas) (.-height image-bitmap))
        (let [context (.getContext canvas "bitmaprenderer")]
          (.transferFromImageBitmap context image-bitmap)
          (.toBlob canvas #(resolve %))))))]
    promise))

(defn- render
  "Renders a thumbnail using it's SVG and returns an ArrayBuffer of the image."
  [payload]
  (p/let [fixed-svg-str (svg-prepare (unchecked-get payload "data") (unchecked-get payload "fonts"))]
    (let [uri (wapi/create-uri (wapi/create-blob fixed-svg-str "image/svg+xml"))
          promise (create-image uri)]
    (-> (p/then promise
       (fn [image]
         (p/let [image-bitmap (js/createImageBitmap image)]
           (wapi/revoke-uri uri)
           image-bitmap)))
        (p/then #(image-bitmap-to-blob %))
        (p/then #(.arrayBuffer %))))))
    
(defn- dispatch-message
  "Dispatches a message."
  [type payload]
  (case type
    "render" (render payload)))

(defn- handle-message
  "Handles messages from the main thread."
  [event]
  (let [data ^js (.-data event)
        target-origin ^js (.-origin event)]
    (when (= target-origin config/parent-origin)
      (let [type (unchecked-get data "type")]
        (when-not (nil? type)
          (let [id (unchecked-get data "id")
                payload (unchecked-get data "payload")
                promise (dispatch-message type payload)]
            (p/then promise #(send-success id %))
            (p/catch promise #(send-failure id %))))))))

(defn- listen
  "Initializes the listener for messages from the main thread."
  []
  ;; This isn't working
  ;;(events/listen js/window "message" handle-message))
  (.addEventListener js/window "message" handle-message))

(defn- send-message
  "Sends a message to the main thread."
  [message]
   (.postMessage js/parent message config/parent-origin))

(defn- send-answer
  "Sends an answer message."
  [id type payload]
  (let [message #js {:id id
                     :type type
                     :payload payload}]
    (send-message message)))
  
(defn- send-success
  "Sends a success message."
  [id payload]
  (send-answer id "success" payload))

(defn- send-failure
  "Sends a failure message."
  [id payload]
  (send-answer id "failure" payload))

(defn- send-ready
  "Sends a ready message."
  []
  (send-message #js {:type "ready"}))

;; Initializes worker
(defn ^:export init
  []
  (listen)
  (send-ready))
