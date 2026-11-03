package com.delivery.backend.modules.delivery.dto;

import com.delivery.backend.modules.field.FieldRunSource;
import jakarta.validation.constraints.NotNull;

public class LocationUpdateRequest {

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    /**
     * Origine du fix. Le client réel envoie {@code FIELD} (ou omet le champ) ;
     * le simulateur du tableau de bord envoie {@code SIMULATED}. Les runs
     * simulés restent stockés mais ne comptent jamais vers les 150 runs réels.
     */
    private FieldRunSource source = FieldRunSource.FIELD;

    public LocationUpdateRequest() {}

    public LocationUpdateRequest(Double latitude, Double longitude) {
        this(latitude, longitude, FieldRunSource.FIELD);
    }

    public LocationUpdateRequest(Double latitude, Double longitude, FieldRunSource source) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.source = source == null ? FieldRunSource.FIELD : source;
    }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public FieldRunSource getSource() { return source; }
    public void setSource(FieldRunSource source) { this.source = source == null ? FieldRunSource.FIELD : source; }
}
