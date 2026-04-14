package com.emergencymesh.network;

import java.util.List;

public class CityDto {
    public static class Info {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static class CityWithDistricts {
        private String id;
        private String name;
        private List<String> districts;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<String> getDistricts() {
            return districts;
        }
    }
}
