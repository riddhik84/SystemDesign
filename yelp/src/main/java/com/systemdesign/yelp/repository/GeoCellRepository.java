package com.systemdesign.yelp.repository;

import com.systemdesign.yelp.model.GeoCell;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeoCellRepository extends JpaRepository<GeoCell, String> {

    /**
     * Find all businesses whose geohash starts with the given prefix.
     * A longer prefix = smaller cell = tighter radius filter.
     * In production, Elasticsearch handles this with a geo_distance query.
     */
    @Query("SELECT g FROM GeoCell g WHERE g.geohashCell LIKE :prefix% AND g.precision = :precision")
    List<GeoCell> findByGeohashPrefixAndPrecision(
        @Param("prefix") String prefix,
        @Param("precision") int precision
    );

    List<GeoCell> findByBusinessId(String businessId);

    void deleteByBusinessId(String businessId);
}
