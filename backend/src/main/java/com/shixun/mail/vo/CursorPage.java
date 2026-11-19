package com.shixun.mail.vo;
import java.util.List;
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
