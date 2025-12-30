# KML Import for Mission Planning Implementation

## Overview
This implementation adds the ability to import KML (Keyhole Markup Language) files to define survey plot boundaries in the Plan mission screen. Users can now load boundary polygons from KML files exported from Google Earth, Google Maps, or other GIS software.

## Features Implemented

### 1. KML File Parser (`KmlBoundaryParser.kt`)
- **Location**: `app/src/main/java/com/example/aerogcsclone/utils/KmlBoundaryParser.kt`
- **Capabilities**:
  - Parses KML 2.2 standard files
  - Extracts all polygon boundaries from the file
  - Handles nested Document/Folder structures
  - Properly converts KML coordinates (lon,lat,alt) to Android LatLng (lat,lon)
  - Handles whitespace variations in coordinate strings
  - Removes duplicate closing points (KML LinearRing requires first=last point)
  - Returns polygon name for user identification

### 2. Polygon Selection Dialog (`KmlPolygonSelectionDialog.kt`)
- **Location**: `app/src/main/java/com/example/aerogcsclone/ui/components/KmlPolygonSelectionDialog.kt`
- **Features**:
  - Shows when multiple polygons are found in a KML file
  - Displays polygon name and point count for each option
  - Visual selection with highlight
  - User can select which polygon to use as the survey boundary

### 3. PlanScreen Integration
- **Updated File**: `app/src/main/java/com/example/aerogcsclone/uimain/PlanScreen.kt`
- **Changes**:
  - Added file picker launcher using `ActivityResultContracts.OpenDocument()`
  - Supports KML and XML MIME types
  - Handles single polygon (auto-use) and multiple polygon (show selection) cases
  - Centers map on imported boundary after successful import
  - Shows toast messages for success/error feedback

## User Workflow

1. **Start New Mission** → Select "New Plan"
2. **Select Mission Type** → Choose "Grid" for survey mission
3. **Choose Grid Source** → Click "Import KML File"
4. **Select File** → Android file picker opens, select a .kml file
5. **Single Polygon**: Boundary loads directly, map centers on it
6. **Multiple Polygons**: Selection dialog appears, pick one polygon
7. **Review Boundary**: Boundary points appear on map as markers
8. **Edit if needed**: Drag markers to adjust boundary
9. **Generate Grid**: Click "Generate Grid" button when ready
10. **Configure Parameters**: Set spacing, altitude, etc.
11. **Upload Mission**: Upload to drone

## KML File Format Support

### Supported Elements
```xml
<kml>
  <Document>
    <Folder> <!-- Optional nesting -->
      <Placemark>
        <name>Polygon Name</name>
        <Polygon>
          <outerBoundaryIs>
            <LinearRing>
              <coordinates>
                78.273,17.269,0 78.272,17.268,0 ...
              </coordinates>
            </LinearRing>
          </outerBoundaryIs>
        </Polygon>
      </Placemark>
    </Folder>
  </Document>
</kml>
```

### Coordinate Format
- KML uses: `longitude,latitude,altitude` (comma-separated, space between tuples)
- Parser handles: Whitespace variations (spaces, tabs, newlines)
- Parser converts to: Android `LatLng(latitude, longitude)` format

### Sample KML (Working)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
  <Placemark>
    <name>Farm Field A</name>
    <Polygon>
      <outerBoundaryIs>
        <LinearRing>
          <coordinates>
            78.2731431823464,17.2696120022312,0
            78.2730480830983,17.2695866593443,0
            78.2730989501209,17.2693353421743,0
            78.2731719332709,17.2693437898198,0
            78.2731431823464,17.2696120022312,0
          </coordinates>
        </LinearRing>
      </outerBoundaryIs>
    </Polygon>
  </Placemark>
</Document>
</kml>
```

## Technical Notes

### No Additional Permissions Required
- Uses Storage Access Framework (SAF) via `ActivityResultContracts.OpenDocument()`
- Works on all Android versions from minSdk 26+
- No `READ_EXTERNAL_STORAGE` permission needed

### Error Handling
- Invalid XML format: Shows "Invalid KML format" error
- No polygons found: Shows "No valid polygons found" message
- File read errors: Shows specific error message
- Logs detailed errors to Logcat under tag "KmlBoundaryParser"

### Supported File Types
- `.kml` files (application/vnd.google-earth.kml+xml)
- `.xml` files (fallback)
- Wildcard fallback (*/*) for file managers with limited MIME type support

### Not Supported (Current Limitations)
- KMZ files (compressed KML) - requires unzip step
- Inner boundaries (holes in polygons)
- LineString as boundary (only Polygon supported)
- Multi-geometry Placemarks

## Files Changed

| File | Change |
|------|--------|
| `utils/KmlBoundaryParser.kt` | NEW - KML parsing logic |
| `ui/components/KmlPolygonSelectionDialog.kt` | NEW - Multi-polygon selection UI |
| `uimain/PlanScreen.kt` | MODIFIED - File picker and dialog integration |

## Testing

### Test with Sample KML
1. Save the sample KML content (from user's example) to a `.kml` file
2. Transfer to device or use emulator file system
3. In app: New Plan → Grid → Import KML
4. Select the file
5. Verify polygon appears on map with correct boundary points

### Expected Behavior
- Polygon points shown as draggable markers
- Polygon area shown as semi-transparent fill
- "Generate Grid" button becomes enabled when 3+ points exist
- Map auto-centers on first point of imported boundary

