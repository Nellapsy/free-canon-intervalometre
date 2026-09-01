package fr.nellapsy.canonintervallometre.ble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.stockageBoitier: DataStore<Preferences> by preferencesDataStore(name = "boitier")

/**
 * Mémorise l'adresse du boîtier appairé, pour que les sessions suivantes se connectent
 * directement sans repasser par un scan — donc sans remettre le boîtier en mode
 * appairage (F1).
 */
class AdresseBoitier(private val contexte: Context) {

    private val cle = stringPreferencesKey("adresse")

    suspend fun lire(): String? = contexte.stockageBoitier.data.map { it[cle] }.first()

    suspend fun memoriser(adresse: String) {
        contexte.stockageBoitier.edit { it[cle] = adresse }
    }
}
