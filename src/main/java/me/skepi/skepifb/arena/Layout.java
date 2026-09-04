package me.skepi.skepifb.arena;

public enum Layout {
    STRAIGHT,
    DIAGONAL;

    public static Layout fromString(String value) {
        if (value == null) {
            return STRAIGHT;
        }
        switch (value.toLowerCase()) {
            case "diagonal":
            case "inclined":
                return DIAGONAL;
            case "straight":
            default:
                return STRAIGHT;
        }
    }
}
