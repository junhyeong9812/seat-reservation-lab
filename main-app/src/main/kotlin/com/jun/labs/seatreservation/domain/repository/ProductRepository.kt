package com.jun.labs.seatreservation.domain.repository

import com.jun.labs.seatreservation.domain.Product
import org.springframework.data.jpa.repository.JpaRepository

interface ProductRepository : JpaRepository<Product, Long>
