(ns drains.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest are]]
            [drains.core :as d]))

(deftest drain-test
  (are [d input expected] (= expected (d/into d input))
    (d/drain +)
    (range 10)
    45

    (d/drain + 100)
    (range 10)
    145

    (d/drain (filter even?) + 0)
    (range 10)
    20

    (d/drain (completing (fn [n d] (+ (* 10 n) d))) 0)
    [3 1 4 1 5 9 2 6 5]
    314159265

    (d/drain (take 10) + 0)
    (range)
    45))

(deftest drains-test
  (are [d input expected] (= expected (d/into d input))
    (d/drains [(d/drain +)
               (d/drain str)])
    (range 10)
    [45 "0123456789"]

    (d/drains {:min (d/drain min Long/MAX_VALUE)
               :max (d/drain max Long/MIN_VALUE)})
    [3 1 4 1 5 9 2 6 5]
    {:min 1 :max 9}

    (d/drains [(d/drain (take 5) + 0)
               (d/drain (drop 5) + 0)])
    (range 10)
    [10 35]))

(deftest fmap-test
  (are [d input expected] (= expected (d/into d input))
    (d/fmap (fn [sum] {:sum sum})
            (d/drain +))
    (range 10)
    {:sum 45}

    (d/fmap (fn [[min max]] {:min min :max max})
            (d/drains [(d/drain min Long/MAX_VALUE)
                       (d/drain max Long/MIN_VALUE)]))
    [3 1 4 1 5 9 2 6 5]
    {:min 1 :max 9}

    (d/fmap (fn [sum] {:sum sum})
            (d/drain (take 5) + 0))
    (range 10)
    {:sum 10}

    (d/fmap str (d/fmap inc (d/drain +)))
    (range 10)
    "46"))

(deftest combine-with
  (are [d input expected] (= expected (d/into d input))
    (d/combine-with (fn [sum count] (/ sum (double count)))
                    (d/drain +)
                    (d/drain (map (constantly 1)) + 0))
    (range 10)
    4.5))

(deftest with-test
  (are [d input expected] (= expected (d/into d input))
    (d/with (filter even?)
            (d/drain (map inc) + 0))
    (range 1 11)
    35

    (d/with (map inc)
            (d/drain (filter even?) + 0))
    (range 1 11)
    30

    (d/with (filter even?)
            (d/drain (take 5) + 0))
    (range)
    20

    (d/with (take 5)
            (d/drain (filter even?) + 0))
    (range)
    6

    (d/with (filter even?)
            (d/drains [(d/drain min Long/MAX_VALUE)
                       (d/drain max Long/MIN_VALUE)]))
    [3 1 4 1 5 9 2 6 5]
    [2 6]

    (d/with (map inc)
            (d/fmap (fn [strs] (str/join \| strs))
                    (d/drain conj)))
    (range 5)
    "1|2|3|4|5"

    (d/fmap (fn [strs] (str/join \| strs))
            (d/with (map inc)
                    (d/drain conj)))
    (range 5)
    "1|2|3|4|5"

    (d/with (map inc)
            (d/with (map str)
                    (d/drain conj)))
    (range 5)
    ["1" "2" "3" "4" "5"]))

(deftest by-key-test
  (are [d input expected] (= expected (d/into d input))
    (d/by-key #(rem % 3)
              (d/drain conj))
    (range 10)
    {0 [0 3 6 9], 1 [1 4 7],  2 [2 5 8]}

    (d/by-key #(rem % 3)
              (d/drains {:sum (d/drain +)
                         :count (d/drain (map (constantly 1)) + 0)}))
    (range 10)
    {0 {:sum 18 :count 4}
     1 {:sum 12 :count 3}
     2 {:sum 15 :count 3}}

    (d/by-key #(rem % 3)
              (d/fmap (fn [sum] {:sum sum})
                      (d/drain +)))
    (range 10)
    {0 {:sum 18}, 1 {:sum 12}, 2 {:sum 15}}

    (d/by-key #(rem % 3)
              (d/with (take 3)
                      (d/drain conj)))
    (range 10)
    {0 [0 3 6], 1 [1 4 7], 2 [2 5 8]}

    (d/with (take 3)
            (d/by-key #(rem % 3)
                      (d/drain conj)))
    (range 10)
    {0 [0], 1 [1], 2 [2]}))
