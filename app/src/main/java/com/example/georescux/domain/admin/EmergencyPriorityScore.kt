package com.example.georescux.domain.admin

data class EmergencyPriorityStats(
    val totalSosCount: Int,
    val verifiedUserSosCount: Int,
    val unverifiedUserSosCount: Int,
    val emergencyPriorityScore: Int
)

object EmergencyPriorityCalculator {
    fun calculate(
        emergencies: List<EmergencyVerifiable>
    ): EmergencyPriorityStats {
        val total = emergencies.size
        val verified = emergencies.count { it.isVerified }
        val unverified = total - verified
        
        // Priority Score formula: (verified * 2) + (unverified * 1)
        val score = (verified * 2) + (unverified * 1)
        
        return EmergencyPriorityStats(total, verified, unverified, score)
    }
}

interface EmergencyVerifiable {
    val isVerified: Boolean
}
