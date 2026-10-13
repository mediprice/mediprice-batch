package com.mediprice.batch.hira;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

// HIRA API 공통 호출 (4개 API 공용, XML로 받아 item을 Map으로 돌려줌)
@Component
public class HiraClient {

    private final RestClient restClient;
    private final HiraProperties props;

    public HiraClient(RestClient.Builder builder, HiraProperties props) {
        this.restClient = builder.baseUrl(props.baseUrl()).build();
        this.props = props;
    }

    // HIRA API 한 페이지 호출. path 예: "/codeInfoService/getAddrCodeList", serviceKey는 자동으로 붙음
    public HiraPage get(String path, Map<String, String> params) {
        // retrieve()는 4xx면 예외부터 던져 오류 XML을 못 읽음 → exchange()로 본문 그대로 받음
        byte[] body = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path).queryParam("serviceKey", props.serviceKey());
                    params.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .exchange((request, response) -> response.getBody().readAllBytes());
        return parse(body);
    }

    private HiraPage parse(byte[] body) {
        Element root = readXml(body).getDocumentElement();

        // 게이트웨이 오류 (키 오류, 트래픽 초과 등): 루트 태그가 다름
        if ("OpenAPI_ServiceResponse".equals(root.getTagName())) {
            throw new HiraApiException(text(root, "returnReasonCode"), text(root, "returnAuthMsg"));
        }

        // 정상 구조인데 결과 코드가 00이 아님
        String resultCode = text(root, "resultCode");
        if (!"00".equals(resultCode)) {
            throw new HiraApiException(resultCode, text(root, "resultMsg"));
        }

        List<Map<String, String>> items = new ArrayList<>();
        NodeList itemNodes = root.getElementsByTagName("item");
        for (int i = 0; i < itemNodes.getLength(); i++) {
            items.add(toMap((Element) itemNodes.item(i)));
        }

        String totalCount = text(root, "totalCount");
        return new HiraPage(items, totalCount.isEmpty() ? 0 : Integer.parseInt(totalCount));
    }

    // <item>의 자식 태그를 {태그 이름: 값} Map으로 바꿈
    private Map<String, String> toMap(Element item) {
        Map<String, String> map = new LinkedHashMap<>();
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                map.put(child.getNodeName(), child.getTextContent().trim());
            }
        }
        return map;
    }

    // 태그를 찾아 글자를 꺼냄. 없으면 빈 문자열
    private String text(Element root, String tagName) {
        NodeList nodes = root.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private Document readXml(byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 외부 XML로 서버 파일을 읽는 공격(XXE) 차단
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            String preview = new String(body, StandardCharsets.UTF_8);
            throw new HiraApiException("PARSE",
                    "XML이 아닌 응답: " + preview.substring(0, Math.min(200, preview.length())));
        }
    }
}
