package com.trackjourney.ui.screens.disease

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.model.*
import com.trackjourney.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiseaseScreen(
    viewModel: DiseaseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDiseaseDialog by remember { mutableStateOf(false) }
    var showAddPersonDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingDisease by remember { mutableStateOf<Disease?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Tracker", fontWeight = FontWeight.Bold) },
                actions = {
                    // Trash toggle
                    val trashCount = uiState.deletedDiseases.size
                    BadgedBox(
                        badge = {
                            if (trashCount > 0) {
                                Badge { Text("$trashCount") }
                            }
                        }
                    ) {
                        IconButton(onClick = { viewModel.toggleTrashView() }) {
                            Icon(
                                if (uiState.showingTrash) Icons.Filled.RestoreFromTrash
                                else Icons.Outlined.DeleteOutline,
                                contentDescription = "Trash",
                                tint = if (uiState.showingTrash) Error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { showAddGroupDialog = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Add Group")
                    }
                    IconButton(onClick = { showAddPersonDialog = true }) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = "Add Person")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!uiState.showingTrash) {
                FloatingActionButton(
                    onClick = { showAddDiseaseDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Disease")
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Person selector chips
            PersonSelectorRow(
                persons = uiState.persons,
                selectedPerson = uiState.selectedPerson,
                onPersonSelected = { viewModel.selectPerson(it) },
                onDeletePerson = { viewModel.deletePerson(it) }
            )

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.showingTrash) {
                TrashView(
                    deletedDiseases = uiState.deletedDiseases,
                    onRestore = { viewModel.restoreDisease(it.id) },
                    onPermanentDelete = { viewModel.permanentlyDeleteDisease(it) }
                )
            } else {
                DiseaseListGrouped(
                    diseasesWithGroup = uiState.diseasesWithGroup,
                    groups = uiState.groups,
                    onDelete = { viewModel.softDeleteDisease(it.id) },
                    onStatusChange = { id, status -> viewModel.updateDiseaseStatus(id, status) },
                    onEdit = { editingDisease = it }
                )
            }
        }
    }

    // Add Disease Dialog
    if (showAddDiseaseDialog) {
        AddDiseaseDialog(
            groups = uiState.groups,
            onDismiss = { showAddDiseaseDialog = false },
            onAdd = { name, desc, groupId, severity, status, notes ->
                viewModel.addDisease(name, desc, groupId, severity, status, notes)
                showAddDiseaseDialog = false
            }
        )
    }

    // Add Person Dialog
    if (showAddPersonDialog) {
        AddPersonDialog(
            onDismiss = { showAddPersonDialog = false },
            onAdd = { name, relationship ->
                viewModel.addPerson(name, relationship)
                showAddPersonDialog = false
            }
        )
    }

    // Add Group Dialog
    if (showAddGroupDialog) {
        AddGroupDialog(
            onDismiss = { showAddGroupDialog = false },
            onAdd = { name, colorHex ->
                viewModel.addGroup(name, colorHex)
                showAddGroupDialog = false
            }
        )
    }

    // Edit Disease Dialog
    editingDisease?.let { disease ->
        EditDiseaseDialog(
            disease = disease,
            groups = uiState.groups,
            onDismiss = { editingDisease = null },
            onSave = { updated ->
                viewModel.updateDisease(updated)
                editingDisease = null
            }
        )
    }
}

// ─────────────────────────────────────────────────────────
//  PERSON SELECTOR
// ─────────────────────────────────────────────────────────

@Composable
private fun PersonSelectorRow(
    persons: List<Person>,
    selectedPerson: Person?,
    onPersonSelected: (Person) -> Unit,
    onDeletePerson: (Person) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        persons.forEach { person ->
            val isSelected = selectedPerson?.id == person.id
            FilterChip(
                selected = isSelected,
                onClick = { onPersonSelected(person) },
                label = {
                    Text(
                        person.name,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        if (person.isSelf) Icons.Filled.Person else Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (!person.isSelf && isSelected) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onDeletePerson(person) }
                        )
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
//  GROUPED DISEASE LIST
// ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiseaseListGrouped(
    diseasesWithGroup: List<DiseaseWithGroup>,
    groups: List<DiseaseGroup>,
    onDelete: (Disease) -> Unit,
    onStatusChange: (String, DiseaseStatus) -> Unit,
    onEdit: (Disease) -> Unit
) {
    if (diseasesWithGroup.isEmpty()) {
        EmptyDiseaseView()
        return
    }

    // Group diseases by their group (null group = "Ungrouped")
    val grouped = diseasesWithGroup.groupBy { it.group?.name ?: "Ungrouped" }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        grouped.forEach { (groupName, diseases) ->
            val isExpanded = expandedGroups.getOrPut(groupName) { true }
            val groupColor = diseases.firstOrNull()?.group?.let { Color(it.colorHex) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant

            item(key = "header_$groupName") {
                GroupHeader(
                    groupName = groupName,
                    count = diseases.size,
                    color = groupColor,
                    isExpanded = isExpanded,
                    onToggle = { expandedGroups[groupName] = !isExpanded }
                )
            }

            if (isExpanded) {
                items(
                    items = diseases,
                    key = { it.disease.id }
                ) { diseaseWithGroup ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                onDelete(diseaseWithGroup.disease)
                                true
                            } else {
                                false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Error.copy(alpha = 0.15f))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = Error
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false
                    ) {
                        DiseaseCard(
                            disease = diseaseWithGroup.disease,
                            group = diseaseWithGroup.group,
                            onStatusChange = onStatusChange,
                            onEdit = onEdit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    groupName: String,
    count: Int,
    color: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        color = color.copy(alpha = 0.06f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = color.copy(alpha = 0.2f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "$count",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                groupName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = color,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
//  DISEASE CARD
// ─────────────────────────────────────────────────────────

@Composable
private fun DiseaseCard(
    disease: Disease,
    group: DiseaseGroup?,
    onStatusChange: (String, DiseaseStatus) -> Unit,
    onEdit: (Disease) -> Unit
) {
    val severityColor = Color(disease.severity.colorHex)
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    var showStatusMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onEdit(disease) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top row: Name + Severity badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Severity indicator dot
                Surface(
                    shape = CircleShape,
                    color = severityColor,
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    disease.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Status chip
                Box {
                    SuggestionChip(
                        onClick = { showStatusMenu = true },
                        label = {
                            Text(
                                disease.status.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false }
                    ) {
                        DiseaseStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.label) },
                                onClick = {
                                    onStatusChange(disease.id, status)
                                    showStatusMenu = false
                                },
                                leadingIcon = {
                                    if (disease.status == status) {
                                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Description
            if (disease.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    disease.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom row: Creation date + Severity label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Created ${dateFormat.format(Date(disease.createdAt))}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    disease.severity.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = severityColor
                )
            }

            // Recovery date if recovered
            if (disease.status == DiseaseStatus.RECOVERED && disease.recoveryDate != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Walking
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Recovered ${dateFormat.format(Date(disease.recoveryDate))}",
                        fontSize = 11.sp,
                        color = Walking,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Notes
            if (disease.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    disease.notes,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
//  TRASH VIEW
// ─────────────────────────────────────────────────────────

@Composable
private fun TrashView(
    deletedDiseases: List<Disease>,
    onRestore: (Disease) -> Unit,
    onPermanentDelete: (Disease) -> Unit
) {
    if (deletedDiseases.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Trash is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Text(
                    "Deleted diseases will appear here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                "Deleted Diseases",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        items(deletedDiseases, key = { it.id }) { disease ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Error.copy(alpha = 0.04f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            disease.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        disease.deletedAt?.let { deletedAt ->
                            Text(
                                "Deleted ${dateFormat.format(Date(deletedAt))}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    IconButton(onClick = { onRestore(disease) }) {
                        Icon(
                            Icons.Filled.RestoreFromTrash,
                            contentDescription = "Restore",
                            tint = Walking
                        )
                    }
                    IconButton(onClick = { onPermanentDelete(disease) }) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = "Delete permanently",
                            tint = Error
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
//  EMPTY STATE
// ─────────────────────────────────────────────────────────

@Composable
private fun EmptyDiseaseView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Healing,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "No diseases tracked",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                "Tap + to add a health condition",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
//  ADD DISEASE DIALOG
// ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDiseaseDialog(
    groups: List<DiseaseGroup>,
    onDismiss: () -> Unit,
    onAdd: (name: String, description: String, groupId: String?, severity: DiseaseSeverity, status: DiseaseStatus, notes: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf(DiseaseSeverity.MILD) }
    var status by remember { mutableStateOf(DiseaseStatus.ACTIVE) }
    var notes by remember { mutableStateOf("") }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    var severityMenuExpanded by remember { mutableStateOf(false) }
    var statusMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Disease", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Disease name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                // Group selector
                Box {
                    OutlinedTextField(
                        value = groups.find { it.id == selectedGroupId }?.name ?: "Select group",
                        onValueChange = {},
                        label = { Text("Group") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { groupMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = groupMenuExpanded,
                        onDismissRequest = { groupMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No group") },
                            onClick = {
                                selectedGroupId = null
                                groupMenuExpanded = false
                            }
                        )
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    selectedGroupId = group.id
                                    groupMenuExpanded = false
                                },
                                leadingIcon = {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(group.colorHex),
                                        modifier = Modifier.size(12.dp)
                                    ) {}
                                }
                            )
                        }
                    }
                }

                // Severity selector
                Box {
                    OutlinedTextField(
                        value = severity.label,
                        onValueChange = {},
                        label = { Text("Severity") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { severityMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { severityMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = severityMenuExpanded,
                        onDismissRequest = { severityMenuExpanded = false }
                    ) {
                        DiseaseSeverity.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, color = Color(s.colorHex)) },
                                onClick = {
                                    severity = s
                                    severityMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Status selector
                Box {
                    OutlinedTextField(
                        value = status.label,
                        onValueChange = {},
                        label = { Text("Status") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { statusMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { statusMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false }
                    ) {
                        DiseaseStatus.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = {
                                    status = s
                                    statusMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, description, selectedGroupId, severity, status, notes) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────
//  EDIT DISEASE DIALOG
// ─────────────────────────────────────────────────────────

@Composable
private fun EditDiseaseDialog(
    disease: Disease,
    groups: List<DiseaseGroup>,
    onDismiss: () -> Unit,
    onSave: (Disease) -> Unit
) {
    var name by remember { mutableStateOf(disease.name) }
    var description by remember { mutableStateOf(disease.description) }
    var selectedGroupId by remember { mutableStateOf(disease.groupId) }
    var severity by remember { mutableStateOf(disease.severity) }
    var status by remember { mutableStateOf(disease.status) }
    var notes by remember { mutableStateOf(disease.notes) }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    var severityMenuExpanded by remember { mutableStateOf(false) }
    var statusMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Disease", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Disease name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                // Group selector
                Box {
                    OutlinedTextField(
                        value = groups.find { it.id == selectedGroupId }?.name ?: "No group",
                        onValueChange = {},
                        label = { Text("Group") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { groupMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = groupMenuExpanded,
                        onDismissRequest = { groupMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No group") },
                            onClick = {
                                selectedGroupId = null
                                groupMenuExpanded = false
                            }
                        )
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    selectedGroupId = group.id
                                    groupMenuExpanded = false
                                },
                                leadingIcon = {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(group.colorHex),
                                        modifier = Modifier.size(12.dp)
                                    ) {}
                                }
                            )
                        }
                    }
                }

                // Severity
                Box {
                    OutlinedTextField(
                        value = severity.label,
                        onValueChange = {},
                        label = { Text("Severity") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { severityMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { severityMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = severityMenuExpanded,
                        onDismissRequest = { severityMenuExpanded = false }
                    ) {
                        DiseaseSeverity.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, color = Color(s.colorHex)) },
                                onClick = {
                                    severity = s
                                    severityMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Status
                Box {
                    OutlinedTextField(
                        value = status.label,
                        onValueChange = {},
                        label = { Text("Status") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { statusMenuExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { statusMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false }
                    ) {
                        DiseaseStatus.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = {
                                    status = s
                                    statusMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        disease.copy(
                            name = name,
                            description = description,
                            groupId = selectedGroupId,
                            severity = severity,
                            status = status,
                            notes = notes,
                            recoveryDate = if (status == DiseaseStatus.RECOVERED && disease.recoveryDate == null)
                                System.currentTimeMillis() else disease.recoveryDate
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────
//  ADD PERSON DIALOG
// ─────────────────────────────────────────────────────────

@Composable
private fun AddPersonDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, relationship: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Person", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("Relationship (e.g., Spouse, Child)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, relationship) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────
//  ADD GROUP DIALOG
// ─────────────────────────────────────────────────────────

@Composable
private fun AddGroupDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, colorHex: Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(0xFF607D8B) }
    val colors = listOf(
        0xFF2196F3, 0xFF4CAF50, 0xFFFF9800, 0xFFE53935,
        0xFF9C27B0, 0xFFFF5722, 0xFF607D8B, 0xFF00BCD4
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Group", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Color", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { color ->
                        Surface(
                            shape = CircleShape,
                            color = Color(color),
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { selectedColor = color }
                        ) {
                            if (selectedColor == color) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
