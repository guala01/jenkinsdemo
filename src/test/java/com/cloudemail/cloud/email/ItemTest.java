package com.cloudemail.cloud.email;

import org.junit.jupiter.api.Test;

import com.cloudemail.cloud.email.model.Item;

import static org.junit.jupiter.api.Assertions.*;

class ItemTest {

    @Test
    void itemShouldHaveCorrectProperties() {
        Item item = new Item();
        item.setItemId("123");
        item.setEnhancementLevel("5");
        item.setPrice("1000");
        item.setTimestamp("2024-01-01");

        assertEquals("123", item.getItemId());
        assertEquals("5", item.getEnhancementLevel());
        assertEquals("1000", item.getPrice());
        assertEquals("2024-01-01", item.getTimestamp());
    }
}
