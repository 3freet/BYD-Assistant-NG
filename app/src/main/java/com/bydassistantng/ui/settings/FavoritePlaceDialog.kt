package com.bydassistantng.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.navigation.FavoritePlace
import com.bydassistantng.navigation.FavoritePlaces
import com.bydassistantng.navigation.SavePlaceResult
import com.bydassistantng.ui.AppAlertDialog

/**
 * [text] shown in its own direction inside a screen of either direction (Unicode's first-strong isolate): an address keeps its
 * words in order, and coordinates, which have no letters, read left to right. Without it an Arabic screen showed
 * "10 Downing Street, London" as "Downing Street, London 10" and "51.500728,-0.124625" as "0.124625-,51.500728".
 */
internal fun isolated(text: String): String = "\u2068$text\u2069"

/**
 * Adding a favorite place ([place] null) or changing one: its name, where it is (typed, or where the car is now), and, for
 * one that exists, deleting it. [takenNames] are the other places' names, which are not offered as suggestions.
 */
@Composable
internal fun FavoritePlaceDialog(
    place: FavoritePlace?,
    takenNames: List<String>,
    locating: SettingsViewModel.CarLocating,
    onUseCarLocation: () -> Unit,
    onSave: (FavoritePlace, (SavePlaceResult) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(place?.name.orEmpty()) }
    var destination by remember { mutableStateOf(place?.destination.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    // What the user typed is laid out by its own first letter, and coordinates (no letters) left to right.
    val fieldStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr)
    val nameTaken = stringResource(R.string.favorite_place_name_taken)
    val full = stringResource(R.string.favorite_place_full, FavoritePlaces.MAX_PLACES)
    val suggestions = listOf(stringResource(R.string.favorite_place_suggest_home), stringResource(R.string.favorite_place_suggest_work))
        .filter { suggestion -> takenNames.none { FavoritePlaces.sameName(it, suggestion) } }

    // Where the car was found goes in as the place's destination.
    LaunchedEffect(locating) {
        if (locating is SettingsViewModel.CarLocating.Found) destination = locating.destination
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (place == null) R.string.favorite_place_new else R.string.favorite_place_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(FavoritePlaces.MAX_NAME_CHARS)
                        message = null
                    },
                    label = { Text(stringResource(R.string.favorite_place_name)) },
                    textStyle = fieldStyle,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A new place usually is one of these: a tap instead of typing.
                if (place == null && name.isBlank() && suggestions.isNotEmpty()) {
                    Row {
                        suggestions.forEach { suggestion ->
                            TextButton(onClick = { name = suggestion }) { Text(suggestion) }
                        }
                    }
                }
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it.take(FavoritePlaces.MAX_DESTINATION_CHARS) },
                    label = { Text(stringResource(R.string.favorite_place_destination)) },
                    textStyle = fieldStyle,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                TextButton(
                    enabled = locating != SettingsViewModel.CarLocating.Looking,
                    onClick = onUseCarLocation,
                ) { Text(stringResource(R.string.favorite_place_use_car)) }
                val status = when (locating) {
                    SettingsViewModel.CarLocating.Looking -> stringResource(R.string.favorite_place_locating)
                    SettingsViewModel.CarLocating.NotAllowed -> stringResource(R.string.favorite_place_location_denied)
                    SettingsViewModel.CarLocating.Unavailable -> stringResource(R.string.favorite_place_location_unavailable)
                    else -> null
                }
                (message ?: status)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (place != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && destination.isNotBlank() && locating != SettingsViewModel.CarLocating.Looking,
                onClick = {
                    onSave(FavoritePlace(name, destination)) { result ->
                        when (result) {
                            is SavePlaceResult.Saved -> onDismiss()
                            SavePlaceResult.NameTaken -> message = nameTaken
                            SavePlaceResult.Full -> message = full
                            SavePlaceResult.Empty -> Unit
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
