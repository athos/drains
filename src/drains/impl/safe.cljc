(ns drains.impl.safe
  (:refer-clojure :exclude [group-by])
  (:require [clojure.core :as cc]
            [drains.protocols :as p]
            [drains.impl.reduced :as reduced]
            [drains.impl.unsafe :as unsafe]
            [drains.impl.utils :as utils]))

(defrecord Drain [rf val unsafe-fn]
  p/IDrain
  (-reduced? [this] false)
  (-flush [this input]
    (let [val' (rf val input)]
      (if (reduced? val')
        (reduced/->ReducedDrain (rf @val'))
        (assoc this :val val'))))
  (-residual [this] (rf val))
  p/Attachable
  (-attach [this xf]
    #(assoc this :rf (xf rf) :unsafe-fn nil))
  p/WithUnsafe
  (-with-unsafe [this unsafe-fn]
    #(assoc this :unsafe-fn unsafe-fn))
  p/ToUnsafe
  (->unsafe [this]
    (if unsafe-fn
      (unsafe-fn)
      (unsafe/->UnsafeDrain rf val))))

(defn drain [xform rf init]
  (fn []
    (->Drain (cond-> rf xform xform) init nil)))

(defrecord Drains [drains rf xfs active-keys]
  p/IDrain
  (-reduced? [this] (empty? active-keys))
  (-flush [this input]
    (let [d (rf this input)]
      (if (reduced? d)
        (reduced/->ReducedDrain (p/-residual @d))
        d)))
  (-residual [this]
    (utils/map-vals p/-residual drains))
  p/Attachable
  (-attach [this xf]
    #(assoc this :rf (xf rf) :xfs (cons xf xfs)))
  p/ToUnsafe
  (->unsafe [this]
    (let [ds (utils/map-vals utils/->unsafe drains)
          dimpls (reduce-kv (fn [ds _ d] (conj ds d)) [] ds)]
      (if (empty? xfs)
        (case (count ds)
          2 (unsafe/->UnsafeDrains2 (nth dimpls 0) (nth dimpls 1) false false ds)
          3 (unsafe/->UnsafeDrains3 (nth dimpls 0) (nth dimpls 1) (nth dimpls 2)
                                    false false false ds)
          (unsafe/->UnsafeDrains ds false active-keys))
        (let [rf ((apply comp xfs) p/-update!)]
          (case (count ds)
            2 (unsafe/->UnsafeDrains2Attachable rf (nth dimpls 0) (nth dimpls 1) false false ds)
            3 (unsafe/->UnsafeDrains3Attachable rf (nth dimpls 0) (nth dimpls 1) (nth dimpls 2)
                                                false false false ds)
            (unsafe/->UnsafeDrainsAttachable rf ds false active-keys)))))))

(defn drains [ds]
  (let [ds (cond-> ds (seq? ds) vec)
        insert (fn [this input]
                 (reduce-kv (fn [this k drain]
                              (let [drain' (p/-flush drain input)]
                                (cond-> (assoc-in this [:drains k] drain')
                                  (p/-reduced? drain')
                                  (update :active-keys disj k))))
                            this
                            (:drains this)))]
    (fn []
      (->Drains (utils/map-vals utils/unwrap ds)
                insert
                '()
                (reduce-kv (fn [ks k _] (conj ks k)) #{} ds)))))

(defrecord Fmap [f drain]
  p/IDrain
  (-reduced? [this]
    (p/-reduced? drain))
  (-flush [this input]
    (update this :drain p/-flush input))
  (-residual [this]
    (f (p/-residual drain)))
  p/Attachable
  (-attach [this xf]
    #(assoc this :drain (utils/unwrap (p/-attach drain xf))))
  p/ToUnsafe
  (->unsafe [this]
    (unsafe/->UnsafeFmap f (utils/->unsafe drain) false)))

(defn fmap [f d]
  (fn [] (->Fmap f (utils/unwrap d))))

(defrecord GroupBy [key-fn rf xfs drain drains reduced?]
  p/IDrain
  (-reduced? [this] reduced?)
  (-flush [this input]
    (let [d (rf this input)]
      (if (cc/reduced? d)
        (assoc @d :reduced? true)
        d)))
  (-residual [this]
    (utils/map-vals p/-residual drains))
  p/Attachable
  (-attach [this xf]
    #(assoc this :rf (xf rf) :xfs (cons xf xfs)))
  p/ToUnsafe
  (->unsafe [this]
    (if (empty? xfs)
      (unsafe/->UnsafeGroupBy key-fn drain {})
      (let [rf ((apply comp xfs) p/-update!)]
        (unsafe/->UnsafeGroupByAttachable rf key-fn drain {})))))

(defn group-by [key-fn d]
  (letfn [(insert [this input]
            (let [key (key-fn input)]
              (if-let [d (get (:drains this) key)]
                (update-in this [:drains key] p/-flush input)
                (let [d (utils/unwrap d)
                      d' (p/-flush d input)]
                  (assoc-in this [:drains key] d')))))]
    (fn [] (->GroupBy key-fn insert '() d {} false))))
