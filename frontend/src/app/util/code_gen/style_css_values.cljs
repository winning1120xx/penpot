;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.util.code-gen.style-css-values
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]))

(defn fill->color
  [{:keys [fill-color fill-opacity fill-color-gradient]}]
  {:color fill-color
   :opacity fill-opacity
   :gradient fill-color-gradient})

(defmulti get-value
  (fn [property _shape _objects] property))

(defmethod get-value :position
  [_ shape objects]
  (cond
    (and (ctl/any-layout-immediate-child? objects shape)
         (not (ctl/layout-absolute? shape))
         (or (cph/group-shape? shape)
             (cph/frame-shape? shape)))
    :relative

    (and (ctl/any-layout-immediate-child? objects shape)
         (not (ctl/layout-absolute? shape)))
    nil

    :else
    :absolute))

(defn get-shape-position
  [shape objects coord]
  (let [shape-value (-> shape :selrect coord)
        parent-value (dm/get-in objects [(:parent-id shape) :selrect coord])]
    (when-not (or (cph/root-frame? shape)
                  (ctl/any-layout-immediate-child? objects shape)
                  (ctl/layout-absolute? shape))
      (- shape-value parent-value))))

(defmethod get-value :left
  [_ shape objects]
  (get-shape-position shape objects :x))

(defmethod get-value :top
  [_ shape objects]
  (get-shape-position shape objects :y))

(defn get-shape-size
  [shape type]
  (let [sizing (if (= type :width)
                 (:layout-item-h-sizing shape)
                 (:layout-item-v-sizing shape))]
    (cond
      (or (= sizing :fill) (= sizing :auto))
      sizing

      (some? (:selrect shape))
      (-> shape :selrect type)

      (some? (get shape type))
      (get shape type))))

(defmethod get-value :width
  [_ shape _]
  (get-shape-size shape :width))

(defmethod get-value :height
  [ shape _]
  (get-shape-size shape :height))

(defmethod get-value :transform
  [_ {:keys [transform] :as shape} _]
  (when (and (some? transform) (not (gmt/unit? transform)))
    (dm/str transform)))

(defn background?
  [shape]
  (and (not (cph/path-shape? shape))
       (not (cph/mask-shape? shape))
       (not (cph/bool-shape? shape))
       (not (cph/svg-raw-shape? shape))
       (nil? (:svg-attrs shape))))

(defmethod get-value :background
  [_ {:keys [fills] :as shape} _]
  (let [single-fill? (= (count fills) 1)
        ffill (first fills)
        gradient? (some? (:fill-color-gradient ffill))]
    (when (and (background? shape) single-fill? gradient?)
      (fill->color ffill))))

(defmethod get-value :background-color
  [_ {:keys [fills] :as shape} _]
  (let [single-fill? (= (count fills) 1)
        ffill (first fills)
        gradient? (some? (:fill-color-gradient ffill))]
    (when (and (background? shape) single-fill? (not gradient?))
      (fill->color ffill))))

(defmethod get-value :background-image
  [_ {:keys [fills] :as shape} _]
  (when (and (background? shape) (> (count fills) 1))
    (->> fills
         (map fill->color))))

(defn get-stroke-data
  [stroke]
  (let [width (:stroke-width stroke)
        style (:stroke-style stroke)
        color {:color (:stroke-color stroke)
               :opacity (:stroke-opacity stroke)
               :gradient (:stroke-color-gradient stroke)}]

    (when (and (some? stroke) (not= :none (:stroke-style stroke)))
      {:color color
       :style style
       :width width})))

(defmethod get-value :border
  [_ shape _]
  (get-stroke-data (first (:strokes shape))))

(defmethod get-value :border-radius
  [_ {:keys [rx r1 r2 r3 r4]} _]
  (cond
    (some? rx)
    [rx]

    (every? some? [r1 r2 r3 r4])
    [r1 r2 r3 r4]))

(defmethod get-value :box-shadow
  [_ shape _]
  (:shadow shape))

(defmethod get-value :filter
  [_ shape _]
  (get-in shape [:blur :value]))

(defmethod get-value :display
  [_ shape _]
  (cond
    (ctl/flex-layout? shape) "flex"
    (ctl/grid-layout? shape) "grid"))

(defmethod get-value :flex-direction
  [_ shape _]
  (:layout-flex-dir shape))

(defmethod get-value :align-items
  [_ shape _]
  (:layout-align-items shape))

(defmethod get-value :align-content
  [_ shape _]
  (:layout-align-content shape))

(defmethod get-value :justify-items
  [_ shape _]
  (:layout-justify-items shape))

(defmethod get-value :justify-content
  [_ shape _]
  (:layout-justify-content shape))

(defmethod get-value :flex-wrap
  [_ shape _]
  (:layout-wrap-type shape))

(defmethod get-value :gap
  [_ shape _]
  (let [[g1 g2] (ctl/gaps shape)]
    (when (or (not= g1 0) (not= g2 0))
      [g1 g2])))

(defmethod get-value :padding
  [_ {:keys [layout-padding]} _]
  (when (some? layout-padding)
    (let [default-padding {:p1 0 :p2 0 :p3 0 :p4 0}
          {:keys [p1 p2 p3 p4]} (merge default-padding layout-padding)]
      (when (or (not= p1 0) (not= p2 0) (not= p3 0) (not= p4 0))
        [p1 p2 p3 p4]))))

(defmethod get-value :grid-template-rows
  [_ shape _]
  (:layout-grid-rows shape))

(defmethod get-value :grid-template-columns
  [_ shape _]
  (:layout-grid-columns shape))

(defn get-grid-coord
  [shape objects prop span-prop]
  (when (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))]
      (if (> (get cell span-prop) 1)
        (dm/str (get cell prop) " / " (+ (get cell prop) (get cell span-prop)))
        (get cell prop)))))

(defmethod get-value :grid-column
  [_ shape objects]
  (get-grid-coord shape objects :column :column-span))

(defmethod get-value :grid-row
  [_ shape objects]
  (get-grid-coord shape objects :row :row-span))

(defmethod get-value :flex-shrink
  [_ shape objects]
  (when (and (ctl/flex-layout-immediate-child? objects shape)
             (not= :fill (:layout-item-h-sizing shape))
             (not= :fill (:layout-item-v-sizing shape))
             (not= :auto (:layout-item-h-sizing shape))
             (not= :auto (:layout-item-v-sizing shape)))
    0))

(defmethod get-value :margin
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-margin shape)))

(defmethod get-value :max-height
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-max-h shape)))

(defmethod get-value :min-height
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-min-h shape)))

(defmethod get-value :max-width
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-max-w shape)))

(defmethod get-value :min-width
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-min-w shape)))

(defmethod get-value :align-self
  [_ shape objects]
  (cond
    (ctl/flex-layout-immediate-child? objects shape)
    (:layout-item-align-self shape)

    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          align-self (:align-self cell)]
      (when (not= align-self :auto) align-self))))

(defmethod get-value :justify-self
  [_ shape objects]
  (cond
    (ctl/grid-layout-immediate-child? objects shape)
    (let [parent (get objects (:parent-id shape))
          cell (ctl/get-cell-by-shape-id parent (:id shape))
          justify-self (:justify-self cell)]
      (when (not= justify-self :auto) justify-self))))

(defmethod get-value :default
  [property shape _]
  (get shape property))


