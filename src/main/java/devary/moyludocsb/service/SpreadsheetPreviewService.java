package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Service
public class SpreadsheetPreviewService {

    public SpreadsheetData parse(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<SheetData> sheets = new ArrayList<>();
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : workbook) {
                List<List<CellData>> rows = new ArrayList<>();
                int maxColumns = 0;
                for (Row row : sheet) {
                    int lastCell = Math.max(row.getLastCellNum(), 0);
                    maxColumns = Math.max(maxColumns, lastCell);
                    List<CellData> values = new ArrayList<>();
                    for (int i = 0; i < lastCell; i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell == null) {
                            values.add(new CellData("", false, false, null, null, null, ""));
                            continue;
                        }
                        CellStyle style = cell.getCellStyle();
                        Font font = workbook.getFontAt(style.getFontIndex());
                        String bg = null;
                        if (style.getFillPattern() == FillPatternType.SOLID_FOREGROUND
                                && style.getFillForegroundColor() != IndexedColors.AUTOMATIC.getIndex()) {
                            bg = "E5E7EB";
                        }
                        Hyperlink hyperlink = cell.getHyperlink();
                        values.add(new CellData(
                                formatter.formatCellValue(cell),
                                font.getBold(),
                                font.getItalic(),
                                font.getFontHeightInPoints() > 0 ? (double) font.getFontHeightInPoints() : null,
                                bg,
                                hyperlink != null ? hyperlink.getAddress() : null,
                                css(font.getBold(), font.getItalic(), font.getFontHeightInPoints() > 0 ? (double) font.getFontHeightInPoints() : null, bg)));
                    }
                    rows.add(values);
                }
                sheets.add(new SheetData(sheet.getSheetName(), rows, maxColumns, extractImages(sheet)));
            }
            return new SpreadsheetData(sheets);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse XLSX", e);
        }
    }

    private List<SheetImage> extractImages(Sheet sheet) {
        List<SheetImage> images = new ArrayList<>();
        if (sheet.getDrawingPatriarch() instanceof XSSFDrawing drawing) {
            for (XSSFShape shape : drawing.getShapes()) {
                if (shape instanceof XSSFPicture picture) {
                    byte[] data = picture.getPictureData().getData();
                    images.add(new SheetImage(
                            picture.getPictureData().suggestFileExtension(),
                            Base64.getEncoder().encodeToString(data)));
                }
            }
        }
        return images;
    }

    private String css(boolean bold, boolean italic, Double fontSize, String background) {
        StringBuilder css = new StringBuilder();
        if (bold) css.append("font-weight:bold;");
        if (italic) css.append("font-style:italic;");
        if (fontSize != null) css.append("font-size:").append(fontSize).append("pt;");
        if (background != null && !background.isBlank()) css.append("background:#").append(background.replaceFirst("^FF", "")).append(";");
        return css.toString();
    }

    public record SpreadsheetData(List<SheetData> sheets) {}
    public record SheetData(String name, List<List<CellData>> rows, int columnCount, List<SheetImage> images) {}
    public record CellData(String value, boolean bold, boolean italic, Double fontSize, String background, String hyperlink, String css) {}
    public record SheetImage(String format, String base64Data) {}
}
