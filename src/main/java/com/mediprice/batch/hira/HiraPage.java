package com.mediprice.batch.hira;

import java.util.List;
import java.util.Map;

public record HiraPage(List<Map<String, String>> items, int totalCount) {
}
