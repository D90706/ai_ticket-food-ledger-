package com.example.ledger.category;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryMapper categoryMapper;

    @GetMapping
    public ApiResponse<List<Category>> list() {
        return ApiResponse.ok(categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSortNo)));
    }
}