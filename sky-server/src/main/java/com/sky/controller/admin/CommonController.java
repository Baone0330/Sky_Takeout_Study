package com.sky.controller.admin;

import com.sky.constant.MessageConstant;
import com.sky.result.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 通用接口
 */
@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
@Slf4j
public class CommonController {

    @Value("${sky.upload.local-path}")
    private String localPath;

    @Value("${sky.upload.public-url}")
    private String publicUrl;

    /**
     * 文件上传到本地
     *
     * @param file
     * @return
     */
    @PostMapping("/upload")
    @ApiOperation("文件上传")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        log.info("文件上传：{}", file.getOriginalFilename());

        try {
            //文件不能为空
            if (file.isEmpty()) {
                return Result.error("文件不能为空");
            }

            //获取原始文件名
            String originalFilename = file.getOriginalFilename();

            //截取文件后缀
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));

            //拼接出新的文件名
            String objectName = UUID.randomUUID().toString() + extension;

            //创建本地存储目录
            Path uploadDirectory = Paths.get(localPath);
            Files.createDirectories(uploadDirectory);

            //生成文件保存路径
            Path targetPath = uploadDirectory.resolve(objectName);

            //保存文件到本地
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath);
            }

            //拼接图片访问地址
            String filePath = publicUrl + "/" + objectName;

            log.info("本地图片上传成功：{}", filePath);

            //返回图片URL
            return Result.success(filePath);

        } catch (Exception e) {
            log.error("文件上传失败：{}", e);
        }

        return Result.error(MessageConstant.UPLOAD_FAILED);
    }
}
