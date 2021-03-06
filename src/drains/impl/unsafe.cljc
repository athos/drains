(ns drains.impl.unsafe
  (:require [clojure.core :as cc]
            [drains.protocols :as p]
            #?(:clj [drains.impl.macros :refer [def-unsafe-drains-n def-attachable-drain]]
               :cljs [drains.impl.macros :refer-macros [def-unsafe-drains-n def-attachable-drain]])
            [drains.impl.reduced :as reduced]
            [drains.impl.utils :as utils]))

(deftype UnsafeDrain [rf ^:unsynchronized-mutable val]
  p/IDrain
  (-reduced? [this] false)
  (-flush [this input]
    (let [val' (rf val input)]
      (if (cc/reduced? val')
        (reduced/->ReducedDrain (rf @val'))
        (do (set! val val')
            this))))
  (-residual [this] (rf val)))

;; FIXME: we can't change mutable fields from within fn exprs, so have to bypass
;; that restriction by using dedicated protocol methods

(defprotocol DrainUpdater
  (update-drain! [this k drain]))

(defprotocol ActiveKeyUpdater
  (disj-active-key! [this k]))

(defprotocol ReducedUpdater
  (update-reduced! [this]))

;; defines
;;  - UnsafeDrains
;;  - UnsafeDrainsAttachable
(def-attachable-drain UnsafeDrains [^:unsynchronized-mutable ds
                                    ^:unsynchronized-mutable reduced?
                                    ^:unsynchronized-mutable active-keys]
  {:-reduced? ([this] reduced?)
   :-residual ([this]
               (reduce-kv (fn [ds k drain] (assoc ds k (p/-residual drain))) ds ds))
   :-flush ([this input]
            (reduce-kv (fn [_ k drain]
                         (let [drain' (p/-flush drain input)]
                           (when-not (identical? drain drain')
                             (update-drain! this k drain')
                             (when (p/-reduced? drain')
                               (disj-active-key! this k)
                               (when-not (seq (.-active-keys this))
                                 (update-reduced! this))))))
                       nil
                       ds))}
  DrainUpdater
  (update-drain! [this k drain]
    (set! ds (assoc ds k drain)))
  ActiveKeyUpdater
  (disj-active-key! [this k]
    (set! active-keys (disj active-keys k)))
  ReducedUpdater
  (update-reduced! [this]
    (set! reduced? true)))

;; defines
;;  - UnsafeDrains2
;;  - UnsafeDrains2Attachable
;;  - UnsafeDrains3
;;  - UnsafeDrains3Attachable
(def-unsafe-drains-n 2 3)

(deftype UnsafeFmap [f
                     ^:unsynchronized-mutable d
                     ^:unsynchronized-mutable reduced?]
  p/IDrain
  (-reduced? [this] reduced?)
  (-flush [this input]
    (let [d' (p/-flush d input)]
      (when-not (identical? d d')
        (set! d d')
        (when (p/-reduced? d')
          (set! reduced? true))))
    this)
  (-residual [this]
    (f (p/-residual d))))

;; defines
;;  - UnsafeGroupBy
;;  - UnsafeGroupByAttachable
(def-attachable-drain UnsafeGroupBy [key-fn d ^:unsynchronized-mutable ds]
  {:-reduced? ([this] false)
   :-residual ([this] (reduce-kv #(assoc %1 %2 (p/-residual %3)) ds ds))
   :-flush ([this input]
            (let [key (key-fn input)
                  d (or (get ds key)
                        (let [d (utils/->unsafe (utils/unwrap d))]
                          (set! ds (assoc ds key d))
                          d))
                  d' (p/-flush d input)]
              (when-not (identical? d d')
                (set! ds (assoc ds key d')))))})
