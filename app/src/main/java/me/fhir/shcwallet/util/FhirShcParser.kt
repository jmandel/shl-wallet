package me.fhir.shcwallet.util

import org.json.JSONObject
import org.json.JSONArray
import me.fhir.shcwallet.services.ShlProcessorService // Might be needed for TAG, or define a local one

// Data class for individual attributes to be displayed in the manifest
data class ManifestAttribute(val name: String, val value: String?)

// Data class to hold all extracted information for a single SHC entry in the manifest
data class ExtractedShcDetails(
    val title: String,
    val subtitle: String? = null,
    val tags: Set<String>,
    val attributes: List<ManifestAttribute>,
    val C4DIC_PROFILE_URL: String = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage" // Keep constant accessible
)

object FhirShcParser {
    private const val TAG = "FhirShcParser"
    private const val C4DIC_PROFILE_URL = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage"

    // Helper function to extract display text from a CodeableConcept JSONObject (Moved from HomeViewModel)
    fun getTextFromCodeableConcept(codeableConcept: JSONObject?): String? {
        if (codeableConcept == null) return null
        val text = codeableConcept.optString("text", null)
        if (!text.isNullOrEmpty()) {
            return text
        }
        val codingArray = codeableConcept.optJSONArray("coding")
        if (codingArray != null) {
            for (i in 0 until codingArray.length()) {
                val coding = codingArray.optJSONObject(i)
                val display = coding?.optString("display", null)
                if (!display.isNullOrEmpty()) {
                    return display
                }
            }
        }
        return null
    }

    // Helper function to extract cost string from a costToBeneficiary.valueMoney JSONObject (Moved from HomeViewModel)
    fun getCostString(valueMoney: JSONObject?): String? {
        if (valueMoney == null) return null
        val extensionArray = valueMoney.optJSONArray("extension")
        if (extensionArray != null) {
            for (i in 0 until extensionArray.length()) {
                val ext = extensionArray.optJSONObject(i)
                if (ext?.optString("url") == "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-BeneficiaryCostString-extension") {
                    val valueStr = ext.optString("valueString", null)
                    if (!valueStr.isNullOrEmpty()) return valueStr
                }
            }
        }
        val value = valueMoney.optDouble("value", -1.0)
        val currency = valueMoney.optString("currency", "")
        if (value != -1.0) {
            return "$value $currency".trim().ifEmpty { null }
        }
        return null
    }

    // Main function to parse SHC JSON and extract details for the manifest
    fun extractShcDetailsForManifest(shcJsonString: String, shcDbIdForDisplay: String, addLog: (String) -> Unit): ExtractedShcDetails {
        // Default values, to be overwritten if C4DIC data is found and processed
        var extractedTitle = "SMART Health Credential (ID: $shcDbIdForDisplay)"
        var extractedSubtitle: String? = null
        val allMetaProfileTags = mutableSetOf<String>()
        val attributes = mutableListOf<ManifestAttribute>()

        var policyHolderName: String? = null
        var insuranceId: String? = null
        var planName: String? = null
        var issuerName: String? = null
        var coveredFamilyMembersString: String? = null
        var coverageStatus: String? = null
        var effectiveDate: String? = null
        var groupName: String? = null
        var networkName: String? = null
        var rxBin: String? = null
        var rxPcn: String? = null
        var rxCopay: String? = null
        var foundAndProcessedC4DICCoverage = false

        var totalBundleEntries = 0
        val resourceTypesInShc = mutableSetOf<String>()

        try {
            val shcJson = JSONObject(shcJsonString)
            val vcArray = shcJson.optJSONArray("verifiableCredential")
            if (vcArray != null) {
                for (i in 0 until vcArray.length()) {
                    val jwsString = vcArray.optString(i)
                    if (jwsString.isNotEmpty()) {
                        val fhirBundleJson = SmartHealthCardParser.fromJws(jwsString) // Existing parser
                        if (fhirBundleJson != null) {
                            val bundleEntriesArray = fhirBundleJson.optJSONArray("entry")
                            totalBundleEntries += bundleEntriesArray?.length() ?: 0

                            if (bundleEntriesArray != null) {
                                for (j in 0 until bundleEntriesArray.length()) {
                                    val entryJson = bundleEntriesArray.optJSONObject(j)
                                    val resourceObj = entryJson?.optJSONObject("resource")
                                    val resourceType = resourceObj?.optString("resourceType")
                                    if (!resourceType.isNullOrEmpty()) {
                                        resourceTypesInShc.add(resourceType)
                                    }

                                    val meta = resourceObj?.optJSONObject("meta")
                                    val profiles = meta?.optJSONArray("profile")
                                    if (profiles != null) {
                                        for (k in 0 until profiles.length()) {
                                            profiles.optString(k)?.let { allMetaProfileTags.add(it) }
                                        }
                                    }

                                    if (!foundAndProcessedC4DICCoverage && resourceType == "Coverage" && resourceObj != null && allMetaProfileTags.contains(C4DIC_PROFILE_URL)) {
                                        addLog("Processing C4DIC Coverage resource in SHC ID $shcDbIdForDisplay...")
                                        foundAndProcessedC4DICCoverage = true

                                        val payorArray = resourceObj.optJSONArray("payor")
                                        issuerName = payorArray?.optJSONObject(0)?.optString("display") ?: "Unknown Issuer"

                                        val classArray = resourceObj.optJSONArray("class")
                                        if (classArray != null) {
                                            for (k in 0 until classArray.length()) {
                                                val classObj = classArray.optJSONObject(k)
                                                val typeCode = classObj?.optJSONObject("type")?.optJSONArray("coding")?.optJSONObject(0)?.optString("code")
                                                when (typeCode) {
                                                    "plan" -> planName = classObj.optString("name", planName)
                                                    "group" -> groupName = classObj.optString("name", groupName)
                                                    "network" -> networkName = classObj.optString("name", networkName)
                                                    "rxbin" -> rxBin = classObj.optString("value", rxBin)
                                                    "rxpcn" -> rxPcn = classObj.optString("value", rxPcn)
                                                }
                                            }
                                        }
                                        
                                        val beneficiaryObj = resourceObj.optJSONObject("beneficiary")
                                        val currentBeneficiaryName = beneficiaryObj?.optString("display", "Unknown Beneficiary") ?: "Unknown Beneficiary"
                                        
                                        val identifierArray = resourceObj.optJSONArray("identifier")
                                        var currentBeneficiaryMemberId = "N/A"
                                        if (identifierArray != null) {
                                            for (k in 0 until identifierArray.length()) {
                                                val idObj = identifierArray.optJSONObject(k)
                                                if (idObj?.optJSONObject("type")?.optJSONArray("coding")?.optJSONObject(0)?.optString("code") == "MB") {
                                                    currentBeneficiaryMemberId = idObj.optString("value", "N/A")
                                                    break
                                                }
                                            }
                                        }
                                        insuranceId = currentBeneficiaryMemberId

                                        val subscriberObj = resourceObj.optJSONObject("subscriber")
                                        policyHolderName = subscriberObj?.optString("display", "Unknown Subscriber") ?: "Unknown Subscriber"
                                        val topLevelSubscriberId = resourceObj.optString("subscriberId", null)

                                        extractedTitle = "SMART Health Coverage for $currentBeneficiaryName"
                                        extractedSubtitle = "Insurance ID: $currentBeneficiaryMemberId"

                                        coverageStatus = resourceObj.optString("status", null)
                                        effectiveDate = resourceObj.optJSONObject("period")?.optString("start", null)

                                        val costArray = resourceObj.optJSONArray("costToBeneficiary")
                                        if (costArray != null) {
                                            for (k in 0 until costArray.length()) {
                                                val costObj = costArray.optJSONObject(k)
                                                if (costObj?.optJSONObject("type")?.optJSONArray("coding")?.optJSONObject(0)?.optString("code") == "rx") {
                                                    rxCopay = getCostString(costObj.optJSONObject("valueMoney"))
                                                    break
                                                }
                                            }
                                        }

                                        val beneficiariesListExt = mutableListOf<String>()
                                        val extensionsArray = resourceObj.optJSONArray("extension")
                                        val planBeneficiariesUrl = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-PlanBeneficiaries-extension"
                                        if (extensionsArray != null) {
                                            for (k in 0 until extensionsArray.length()) {
                                                val extObj = extensionsArray.optJSONObject(k)
                                                if (extObj?.optString("url") == planBeneficiariesUrl) {
                                                    val beneficiaryExtensions = extObj.optJSONArray("extension")
                                                    var memberNameExt: String? = null; var memberIdExt: String? = null
                                                    if (beneficiaryExtensions != null) {
                                                        for (l in 0 until beneficiaryExtensions.length()) {
                                                            val detailExt = beneficiaryExtensions.optJSONObject(l)
                                                            when (detailExt?.optString("url")) {
                                                                "name" -> {
                                                                    val humanName = detailExt.optJSONObject("valueHumanName")
                                                                    val family = humanName?.optString("family", "")
                                                                    val givenArray = humanName?.optJSONArray("given")
                                                                    val given = if (givenArray != null && givenArray.length() > 0) givenArray.optString(0, "") else ""
                                                                    memberNameExt = "$given $family".trim().ifEmpty { null }
                                                                }
                                                                "memberId" -> memberIdExt = detailExt.optString("valueId", null)
                                                            }
                                                        }
                                                    }
                                                    if (memberNameExt != null && memberIdExt != null) {
                                                        var role = "Dependent"
                                                        if (memberIdExt == topLevelSubscriberId) role = "Subscriber"
                                                        else if (memberNameExt == currentBeneficiaryName && memberIdExt == currentBeneficiaryMemberId) {
                                                            role = getTextFromCodeableConcept(resourceObj.optJSONObject("relationship")) ?: "Beneficiary"
                                                        }
                                                        beneficiariesListExt.add("$memberNameExt ($role, ID: $memberIdExt)")
                                                    }
                                                }
                                            }
                                        }
                                        coveredFamilyMembersString = if (beneficiariesListExt.isNotEmpty()) beneficiariesListExt.joinToString("; ") else "(No plan beneficiaries listed)"
                                    }
                                }
                            }
                        } else {
                            addLog("Warning: Parsing JWS #${i + 1} (in SHC ID $shcDbIdForDisplay) to FHIR Bundle returned null.")
                            addLog("Problematic JWS (first 100 chars): ${jwsString.take(100)}...")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            addLog("Error during FHIR bundle processing for SHC ID $shcDbIdForDisplay: ${e.message}")
            // Log.e(TAG, "FHIR processing error for SHC ID $shcDbIdForDisplay", e) // Cannot use Android Log here directly without context or proper setup
        }

        attributes.add(ManifestAttribute("FHIR Bundle Entry Count", totalBundleEntries.toString()))
        attributes.add(ManifestAttribute("FHIR Resource Types", if (resourceTypesInShc.isNotEmpty()) resourceTypesInShc.joinToString(", ") else "N/A"))

        val isActuallyC4DIC = allMetaProfileTags.contains(C4DIC_PROFILE_URL)

        if (isActuallyC4DIC && foundAndProcessedC4DICCoverage) {
            policyHolderName?.let { attributes.add(ManifestAttribute("Policy Holder", it)) }
            insuranceId?.let { attributes.add(ManifestAttribute("Insurance ID", it)) }
            planName?.let { attributes.add(ManifestAttribute("Plan Name", it)) }
            issuerName?.let { attributes.add(ManifestAttribute("Issuer", it)) }
            coverageStatus?.let { attributes.add(ManifestAttribute("Status", it)) }
            effectiveDate?.let { attributes.add(ManifestAttribute("Effective Date", it)) }
            groupName?.let { attributes.add(ManifestAttribute("Group Name", it)) }
            networkName?.let { attributes.add(ManifestAttribute("Network Name", it)) }
            rxBin?.let { attributes.add(ManifestAttribute("RxBIN", it)) }
            rxPcn?.let { attributes.add(ManifestAttribute("RxPCN", it)) }
            rxCopay?.let { attributes.add(ManifestAttribute("Rx Copay", it)) }
            
            attributes.add(ManifestAttribute("Full Insurance Coverage Details", null)) // Placeholder for more complex display in Rust
            coveredFamilyMembersString?.let { attributes.add(ManifestAttribute("Covered Family Members", it)) }
        } else if (isActuallyC4DIC && !foundAndProcessedC4DICCoverage) {
            addLog("SHC ID $shcDbIdForDisplay is tagged as C4DIC but no specific C4DIC Coverage resource was processed for detailed attributes.")
            attributes.add(ManifestAttribute("Info", "C4DIC card, detailed attributes not extracted."))
        } else {
            attributes.add(ManifestAttribute("Identifier (SHC DB ID)", shcDbIdForDisplay))
        }
        
        return ExtractedShcDetails(
            title = extractedTitle,
            subtitle = extractedSubtitle,
            tags = allMetaProfileTags.toSet(), // Convert to immutable set for the data class
            attributes = attributes
        )
    }
} 