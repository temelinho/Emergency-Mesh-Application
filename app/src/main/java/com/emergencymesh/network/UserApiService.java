package com.emergencymesh.network;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;

import java.util.List;

public interface UserApiService {

    @POST("api/v1/users/register")
    Call<UserDto.Response> register(@Body UserDto.Request request);

    @POST("api/v1/users/login")
    Call<UserDto.Response> login(@Body UserDto.LoginRequest request);

    @GET("api/v1/locations/cities")
    Call<List<CityDto.Info>> getCities();

    @GET("api/v1/locations/cities/{cityId}/districts")
    Call<List<String>> getDistricts(@Path("cityId") String cityId);

    @PUT("api/v1/users/{id}")
    Call<UserDto.Response> updateUser(@Path("id") String id, @Body UserDto.UpdateRequest request);
}
