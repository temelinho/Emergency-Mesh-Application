package com.emergencymesh.network;

import com.google.gson.annotations.SerializedName;

public class UserDto {

    public static class Request {
        private String username;
        private String phoneNumber;
        private String password;
        private String firstName;
        private String lastName;
        private String city;
        private String district;

        public Request(String username, String phoneNumber, String password, String firstName, String lastName,
                String city, String district) {
            this.username = username;
            this.phoneNumber = phoneNumber;
            this.password = password;
            this.firstName = firstName;
            this.lastName = lastName;
            this.city = city;
            this.district = district;
        }
    }

    public static class UpdateRequest {
        private String username;
        private String phoneNumber;
        private String firstName;
        private String lastName;
        private String city;
        private String district;

        public UpdateRequest(String username, String phoneNumber, String firstName, String lastName,
                String city, String district) {
            this.username = username;
            this.phoneNumber = phoneNumber;
            this.firstName = firstName;
            this.lastName = lastName;
            this.city = city;
            this.district = district;
        }
    }

    public static class LoginRequest {
        private String phoneNumber;
        private String password;

        public LoginRequest(String phoneNumber, String password) {
            this.phoneNumber = phoneNumber;
            this.password = password;
        }
    }

    public static class Response {
        private String id;
        private String username;
        private String phoneNumber;
        private String firstName;
        private String lastName;
        private String city;
        private String district;

        public String getId() {
            return id;
        }

        public String getUsername() {
            return username;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getCity() {
            return city;
        }

        public String getDistrict() {
            return district;
        }
    }
}
