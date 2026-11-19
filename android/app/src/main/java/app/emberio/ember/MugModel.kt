package app.emberio.ember

/**
 * Ember models, told apart by the manufacturer data in their advertisements.
 *
 * [ratedHeatMinutes] is Ember's published battery life keeping a drink warm, and
 * [ratedChargeMinutes] a rough full charge on the coaster. They seed the time-left estimate
 * until Emberio has watched your mug drain (see BatteryEstimator).
 */
enum class MugModel(
    val displayName: String,
    val ratedHeatMinutes: Double,
    val ratedChargeMinutes: Double,
    val levelIsPercent: Boolean = false,
) {
    MUG_2_10OZ("Ember Mug 2", 90.0, 120.0),
    MUG_2_14OZ("Ember Mug 2 (14 oz)", 80.0, 120.0),
    MUG_1_10OZ("Ember Mug", 60.0, 120.0),
    MUG_1_14OZ("Ember Mug (14 oz)", 80.0, 120.0),
    CUP("Ember Cup", 90.0, 120.0),
    TUMBLER("Ember Tumbler", 180.0, 150.0, levelIsPercent = true),
    TRAVEL_MUG("Ember Travel Mug", 180.0, 150.0, levelIsPercent = true),
    UNKNOWN("Ember mug", 80.0, 120.0);

    companion object {
        fun fromCode(code: String?): MugModel = entries.firstOrNull { it.name == code } ?: UNKNOWN

        /**
         * @param manufacturerData the bytes under [EmberProtocol.MANUFACTURER_ID], if any
         * @param serviceUuids service UUIDs in the advertisement
         * @param name the advertised name, as a last resort
         */
        fun detect(manufacturerData: ByteArray?, serviceUuids: Collection<java.util.UUID>, name: String?): MugModel {
            if (serviceUuids.any { it == EmberProtocol.SERVICE_TRAVEL_MUG || it == EmberProtocol.SERVICE_TRAVEL_MUG_OTHER }) {
                return TRAVEL_MUG
            }
            val data = manufacturerData
            if (data != null && data.isNotEmpty()) {
                fromAdvertisement(data)?.let { return it }
            }
            return fromName(name)
        }

        private fun fromAdvertisement(data: ByteArray): MugModel? {
            if (data.size >= 4) {
                val modelId = data[1].toInt() and 0xFF
                val generation = data[2].toInt() and 0xFF
                return when (modelId) {
                    1 -> if (generation < 2) MUG_1_10OZ else MUG_2_10OZ
                    2, 120 -> if (generation < 2) MUG_1_14OZ else MUG_2_14OZ
                    3 -> TRAVEL_MUG
                    8 -> CUP
                    9 -> TUMBLER
                    else -> null
                }
            }
            // Older firmware advertises a single signed big-endian number.
            var single = 0
            for (b in data) single = (single shl 8) or (b.toInt() and 0xFF)
            val bits = data.size * 8
            if (single and (1 shl (bits - 1)) != 0) single -= 1 shl bits
            return when (single) {
                1, 2, 3 -> MUG_1_10OZ
                65 -> MUG_1_14OZ
                -51, -59, -63, -61, -62, 120 -> MUG_2_14OZ
                -60 -> CUP
                -127, -126, -125, -124, -123, -122, -120, -117, -57, -56, -55, -53, -52, 83, 131 -> MUG_2_10OZ
                else -> null
            }
        }

        fun fromName(name: String?): MugModel {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                "travel" in n -> TRAVEL_MUG
                "tumbler" in n -> TUMBLER
                "cup" in n -> CUP
                "ceramic mug" in n -> MUG_2_10OZ
                "mug" in n -> UNKNOWN
                else -> UNKNOWN
            }
        }

        /** Does this advertisement look like an Ember device? */
        fun isEmber(manufacturerData: ByteArray?, serviceUuids: Collection<java.util.UUID>, name: String?): Boolean =
            manufacturerData != null ||
                serviceUuids.any { it in EmberProtocol.SERVICES } ||
                (name?.startsWith("Ember", ignoreCase = true) == true)
    }
}
