;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.selection
  (:require
   [app.common.data :as d]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gte]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.util.quadtree :as qdt]
   [app.worker.impl :as impl]
   [clojure.set :as set]
   [okulary.core :as l]))

(def ^:const padding-percent 0.10)

(defonce state (l/atom {}))

(defn make-index-shape
  [objects parents-index clip-parents-index]
  (fn [index shape]
    ;; FIXME: performance rect
    (let [{:keys [x y width height]}
          (cond
            (and (= :text (:type shape))
                 (some? (:position-data shape))
                 (d/not-empty? (:position-data shape)))
            (gte/position-data-bounding-box shape)

            :else
            (grc/points->rect (:points shape)))
          shape-bound #js {:x x :y y :width width :height height}

          parents      (get parents-index (:id shape))
          clip-parents (get clip-parents-index (:id shape))

          frame   (when (and (not= :frame (:type shape))
                             (not= (:frame-id shape) uuid/zero))
                    (get objects (:frame-id shape)))]
      (qdt/insert index
                  (:id shape)
                  shape-bound
                  (assoc shape
                         :frame frame
                         :clip-parents clip-parents
                         :parents parents)))))

(defn objects-bounds
  "Calculates the bounds of the quadtree given a objects map."
  [objects]
  (-> objects
      (dissoc uuid/zero)
      vals
      gsh/selection-rect))

(defn add-padding-bounds
  "Adds a padding to the bounds defined as a percent in the constant `padding-percent`.
  For a value of 0.1 will add a 20% width increase (2 x padding)"
  [bounds]
  (let [width-pad  (* (:width bounds) padding-percent)
        height-pad (* (:height bounds) padding-percent)]
    (-> bounds
        (update :x - width-pad)
        (update :x1 - width-pad)
        (update :x2 + width-pad)
        (update :y1 - height-pad)
        (update :y2 + height-pad)
        (update :width + width-pad width-pad)
        (update :height + height-pad height-pad))))

(defn- create-index
  [objects]
  (let [shapes             (-> objects (dissoc uuid/zero) vals)
        parents-index      (cp/generate-child-all-parents-index objects)
        clip-parents-index (cp/create-clip-index objects parents-index)

        root-shapes        (cph/get-immediate-children objects uuid/zero)
        bounds             (-> root-shapes gsh/selection-rect add-padding-bounds)

        index-shape        (make-index-shape objects parents-index clip-parents-index)
        initial-quadtree   (qdt/create (clj->js bounds))

        index              (reduce index-shape initial-quadtree shapes)]

    {:index index :bounds bounds}))

(defn- update-index
  [{index :index :as data} old-objects new-objects]
  (let [changes? (fn [id]
                   (not= (get old-objects id)
                         (get new-objects id)))

        changed-ids (into #{}
                          (comp (filter #(not= % uuid/zero))
                                (filter changes?)
                                (mapcat #(into [%] (cph/get-children-ids new-objects %))))
                          (set/union (set (keys old-objects))
                                     (set (keys new-objects))))

        shapes             (->> changed-ids (mapv #(get new-objects %)) (filterv (comp not nil?)))
        parents-index      (cp/generate-child-all-parents-index new-objects shapes)
        clip-parents-index (cp/create-clip-index new-objects parents-index)

        new-index (qdt/remove-all index changed-ids)

        index-shape (make-index-shape new-objects parents-index clip-parents-index)
        index (reduce index-shape new-index shapes)]

    (assoc data :index index)))

(defn- query-index
  [{index :index} rect frame-id full-frame? include-frames? ignore-groups? clip-children?]
  (let [result (-> (qdt/search index (clj->js rect))
                   (es6-iterator-seq))

        ;; Check if the shape matches the filter criteria
        match-criteria?
        (fn [shape]
          (and (not (:hidden shape))
               (or (= :frame (:type shape)) ;; We return frames even if blocked
                   (not (:blocked shape)))
               (or (not frame-id) (= frame-id (:frame-id shape)))
               (case (:type shape)
                 :frame   include-frames?
                 (:bool :group) (not ignore-groups?)
                 true)

               (or (not full-frame?)
                   (not= :frame (:type shape))
                   (and (d/not-empty? (:shapes shape))
                        (gsh/rect-contains-shape? rect shape))
                   (and (empty? (:shapes shape))
                        (gsh/overlaps? shape rect)))))

        overlaps?
        (fn [shape]
          (gsh/overlaps? shape rect))

        overlaps-parent?
        (fn [clip-parents]
          (->> clip-parents (some (comp not overlaps?)) not))]

    ;; Shapes after filters of overlapping and criteria
    (into (d/ordered-set)
          (comp (map #(unchecked-get % "data"))
                (filter match-criteria?)
                (filter overlaps?)
                (filter (if clip-children?
                          (comp overlaps-parent? :clip-parents)
                          (constantly true)))
                (map :id))
          result)))


(defmethod impl/handler :selection/initialize-page-index
  [{:keys [page] :as message}]
  (letfn [(add-page [state {:keys [id objects] :as page}]
            (assoc state id (create-index objects)))]
    (swap! state add-page page)
    nil))

(defmethod impl/handler :selection/update-page-index
  [{:keys [page-id old-page new-page] :as message}]
  (swap! state update page-id
         (fn [index]
           (let [old-objects (:objects old-page)
                 new-objects (:objects new-page)
                 old-bounds  (:bounds index)
                 new-bounds  (objects-bounds new-objects)]

             ;; If the new bounds are contained within the old bounds
             ;; we can update the index. Otherwise we need to
             ;; re-create it.
             (if (and (some? index)
                      (grc/contains-rect? old-bounds new-bounds))
               (update-index index old-objects new-objects)
               (create-index new-objects)))))
  nil)

(defmethod impl/handler :selection/query
  [{:keys [page-id rect frame-id full-frame? include-frames? ignore-groups? clip-children?]
    :or {full-frame? false include-frames? false clip-children? true}
    :as message}]
  (when-let [index (get @state page-id)]
    (query-index index rect frame-id full-frame? include-frames? ignore-groups? clip-children?)))
