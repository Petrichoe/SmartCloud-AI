package com.tianji.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 学霸天梯榜
 * </p>
 *
 * @author 虎哥
 */
@Data //不需要写get,set
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true) //支持链式调用如：PointsBoard board new PointsBoard().setUserId(1L).setPoints(100).setRank(1);
@TableName("points_board") //指定数据库表名
public class PointsBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 榜单id
     */
    @TableId(value = "id", type = IdType.INPUT)//指定主键字段
    private Long id;

    /**
     * 学生id
     */
    private Long userId;

    /**
     * 积分值
     */
    private Integer points;

    /**
     * 名次，只记录赛季前100
     */
    @TableField(exist = false)//标记为非数据库字段
    private Integer rank;

    /**
     * 赛季id
     */
    @TableField(exist = false)
    private Integer season;


}
