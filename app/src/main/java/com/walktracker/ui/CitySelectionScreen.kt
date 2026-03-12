package com.spywalker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spywalker.R
import com.spywalker.data.City
import com.spywalker.data.osm.NominatimSearchResult
import com.spywalker.ui.theme.DarkBackground
import com.spywalker.ui.theme.DarkCard
import com.spywalker.ui.theme.DarkSurfaceVariant
import com.spywalker.ui.theme.Error
import com.spywalker.ui.theme.Primary
import com.spywalker.ui.theme.TextOnDark
import com.spywalker.ui.theme.TextOnDarkSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitySelectionScreen(
    uiState: CitySelectionUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDownloadCity: (NominatimSearchResult) -> Unit,
    onSelectCity: (City) -> Unit,
    onDeleteCity: (City) -> Unit,
    onDismiss: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf<City?>(null) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.city_selection_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextOnDark,
                    navigationIconContentColor = TextOnDark
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
        ) {
            SearchPanel(
                query = uiState.searchQuery,
                isSearching = uiState.isSearching,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch
            )

            if (uiState.isDownloading) {
                DownloadProgressCard(
                    progress = uiState.downloadProgress,
                    status = uiState.downloadStatus
                )
            }

            uiState.error?.let { error ->
                ErrorCard(error = error)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.downloadedCities.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.downloaded_cities)) }
                    items(uiState.downloadedCities) { city ->
                        DownloadedCityCard(
                            city = city,
                            onSelect = { onSelectCity(city) },
                            onDelete = { showDeleteConfirm = city }
                        )
                    }
                }

                if (uiState.searchResults.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_results)) }
                    items(uiState.searchResults) { result ->
                        SearchResultCard(
                            result = result,
                            isDownloading = uiState.isDownloading,
                            onDownload = { onDownloadCity(result) }
                        )
                    }
                }

                if (uiState.isSearching) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Primary)
                        }
                    }
                }

                if (!uiState.isSearching && uiState.searchResults.isEmpty() && uiState.downloadedCities.isEmpty()) {
                    item { EmptyState() }
                }
            }
        }
    }

    showDeleteConfirm?.let { city ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_city_title)) },
            text = { Text(stringResource(R.string.delete_city_message, city.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCity(city)
                        showDeleteConfirm = null
                    }
                ) {
                    Text(stringResource(R.string.delete_action), color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }
}

@Composable
private fun SearchPanel(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(stringResource(R.string.city_search_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Primary)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkCard,
                    unfocusedContainerColor = DarkCard,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedTextColor = TextOnDark,
                    unfocusedTextColor = TextOnDark,
                    focusedPlaceholderColor = TextOnDarkSecondary,
                    unfocusedPlaceholderColor = TextOnDarkSecondary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSearch,
                enabled = query.length >= 3 && !isSearching,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSearching) stringResource(R.string.searching_action) else stringResource(R.string.search_action))
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    progress: Float,
    status: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = status.ifBlank { stringResource(R.string.loading_action) },
                style = MaterialTheme.typography.titleMedium,
                color = TextOnDark,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = Primary,
                trackColor = DarkSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextOnDarkSecondary
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(16.dp),
            color = TextOnDark,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = TextOnDark,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LocationCity,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = TextOnDarkSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.empty_city_title),
                color = TextOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_city_hint),
                color = TextOnDarkSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SearchResultCard(
    result: NominatimSearchResult,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationCity,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.displayName.split(",").first(),
                    color = TextOnDark,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.displayName,
                    color = TextOnDarkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(onClick = onDownload, enabled = !isDownloading) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.download_action),
                    tint = if (isDownloading) TextOnDarkSecondary else Primary
                )
            }
        }
    }
}

@Composable
fun DownloadedCityCard(
    city: City,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val coveragePercent = if (city.totalRoadLengthMeters > 0) {
        (city.exploredRoadLengthMeters / city.totalRoadLengthMeters * 100).toFloat()
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (city.isSelected) Primary.copy(alpha = 0.16f) else DarkCard
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Primary.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (city.isSelected) Icons.Default.CheckCircle else Icons.Default.LocationCity,
                    contentDescription = if (city.isSelected) stringResource(R.string.selected_action) else null,
                    tint = Primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = city.name,
                    color = TextOnDark,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.roads_and_length,
                        city.totalRoadSegments,
                        city.totalRoadLengthMeters / 1000
                    ),
                    color = TextOnDarkSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (coveragePercent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = DarkSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.coverage_label, coveragePercent),
                    color = TextOnDarkSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_action),
                    tint = Error
                )
            }
        }
    }
}
