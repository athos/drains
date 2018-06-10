(ns drains.impl.reduced
  (:require [drains.protocols :as p]))

(deftype ReducedDrain [val]
  p/IDrain
  (-reduced? [this] true)
  (-flush [this _] this)
  (-residual [this] val)
  (-attach [this _] this))
