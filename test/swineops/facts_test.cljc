(ns swineops.facts-test
  (:require [clojure.test :refer [deftest is are testing]]
            [swineops.facts :as facts]))

(deftest supply-category-lookup
  (testing "Lookup valid supply category"
    (let [c (facts/supply-category-by-id "feed")]
      (is (= "feed" (:id c)))
      (is (= "飼料" (:name c)))))

  (testing "Lookup invalid supply category"
    (is (nil? (facts/supply-category-by-id "unknown")))))

(deftest supply-category-cost-thresholds
  (testing "Category-specific cost thresholds"
    (are [id expected] (= expected (:cost-threshold (facts/supply-category-by-id id)))
      "feed"                   500
      "veterinary-supply"      500
      "biosecurity-equipment"  1000)))

(deftest default-cost-threshold-value
  (testing "Default fallback threshold matches the conservative baseline"
    (is (= 500 facts/default-cost-threshold))))

(deftest breed-lookup
  (testing "Lookup valid breed"
    (are [id expected-name] (= expected-name (:name (facts/breed-by-id id)))
      "landrace"  "ランドレース"
      "duroc"     "デュロック"
      "yorkshire" "ヨークシャー"
      "berkshire" "バークシャー"))

  (testing "Lookup invalid breed"
    (is (nil? (facts/breed-by-id "unknown")))))

(deftest biosecurity-concern-lookup
  (testing "Lookup valid biosecurity/notifiable-disease concern"
    (let [c (facts/biosecurity-concern-by-id "asf")]
      (is (= "asf" (:id c)))
      (is (true? (:notifiable c)))))

  (testing "Notifiable flag distinguishes reportable diseases"
    (are [id expected-notifiable?] (= expected-notifiable? (:notifiable (facts/biosecurity-concern-by-id id)))
      "asf"  true
      "csf"  true
      "fmd"  true
      "prrs" false))

  (testing "Lookup invalid concern"
    (is (nil? (facts/biosecurity-concern-by-id "unknown")))))
