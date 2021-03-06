/*
 * Module: r2-lcp-kotlin
 * Developers: Aferdita Muriqi
 *
 * Copyright (c) 2019. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.lcp.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.readium.r2.lcp.*
import org.readium.r2.lcp.BuildConfig.DEBUG
import org.readium.r2.lcp.license.License
import org.readium.r2.lcp.license.LicenseValidation
import org.readium.r2.lcp.license.container.BytesLicenseContainer
import org.readium.r2.lcp.license.container.LicenseContainer
import org.readium.r2.lcp.license.container.createLicenseContainer
import org.readium.r2.lcp.license.model.LicenseDocument
import timber.log.Timber


internal class LicensesService(
    private val licenses: LicensesRepository,
    private val crl: CRLService,
    private val device: DeviceService,
    private val network: NetworkService,
    private val passphrases: PassphrasesService,
    private val context: Context
) : LCPService, CoroutineScope by MainScope() {

    override fun importPublication(lcpl: ByteArray, authentication: LCPAuthenticating?, completion: (LCPImportedPublication?, LCPError?) -> Unit) {
        val container = BytesLicenseContainer(lcpl)
        try {
            retrieveLicense(container, authentication) { license ->
                if (license != null) {
                    launch {
                        try {
                            completion(license.fetchPublication(context), null)
                        } catch (e: Exception) {
                            completion(null, LCPError.wrap(e))
                        }
                    }
                } else {
                    completion(null, null)
                }
            }
        } catch (e:Exception) {
            completion(null, LCPError.wrap(e))
        }
    }

    override fun retrieveLicense(publication: String, authentication: LCPAuthenticating?, completion: (LCPLicense?, LCPError?) -> Unit) {
        try {
            val container = createLicenseContainer(publication)
            retrieveLicense(container, authentication) { license ->
                if (DEBUG) Timber.d("license retrieved ${license?.license}")
                completion(license, null)
            }
        } catch (e:Exception) {
            completion(null, LCPError.wrap(e))
        }
    }

    private fun retrieveLicense(container: LicenseContainer, authentication: LCPAuthenticating?, completion: (License?) -> Unit) {

        var initialData = container.read()
        if (DEBUG) Timber.d("license ${LicenseDocument(data = initialData).json}")

        val validation = LicenseValidation(authentication = authentication, crl = this.crl,
                device = this.device, network = this.network, passphrases = this.passphrases, context = this.context) { licenseDocument ->
            try {
                this.licenses.addLicense(licenseDocument)
            } catch (error: Error) {
                if (DEBUG) Timber.d("Failed to add the LCP License to the local database: $error")
            }
            if (!licenseDocument.data.contentEquals(initialData)) {
                try {
                    container.write(licenseDocument)
                    if (DEBUG) Timber.d("licenseDocument ${licenseDocument.json}")

                    initialData = container.read()
                    if (DEBUG) Timber.d("license ${LicenseDocument(data = initialData).json}")
                    if (DEBUG) Timber.d("Wrote updated License Document in container")
                } catch (error: Error) {
                    if (DEBUG) Timber.d("Failed to write updated License Document in container: $error")
                }
            }

        }

        validation.validate(LicenseValidation.Document.license(initialData)) { documents, error ->
            documents?.let {
                if (DEBUG) Timber.d("validated documents $it")
                try {
                    documents.getContext()
                    completion( License(documents = it, validation = validation, licenses = this.licenses, device = this.device, network = this.network) )
                } catch (e:Exception) {
                    throw e
                }
            }
            error?.let { throw error }

            if (documents == null && error == null) {
                completion(null)
            }
        }
    }

}
