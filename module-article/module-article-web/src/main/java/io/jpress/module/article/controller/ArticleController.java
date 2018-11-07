/**
 * Copyright (c) 2016-2019, Michael Yang 杨福海 (fuhai999@gmail.com).
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jpress.module.article.controller;

import com.jfinal.kit.Ret;
import io.jboot.utils.StrUtils;
import io.jboot.web.controller.annotation.RequestMapping;
import io.jpress.commons.utils.CommonsUtils;
import io.jpress.model.User;
import io.jpress.module.article.kit.ArticleKit;
import io.jpress.module.article.model.Article;
import io.jpress.module.article.model.ArticleCategory;
import io.jpress.module.article.model.ArticleComment;
import io.jpress.module.article.service.ArticleCategoryService;
import io.jpress.module.article.service.ArticleCommentService;
import io.jpress.module.article.service.ArticleService;
import io.jpress.service.OptionService;
import io.jpress.web.base.TemplateControllerBase;
import org.apache.commons.lang.StringEscapeUtils;

import javax.inject.Inject;
import java.util.List;

/**
 * @author Michael Yang 杨福海 （fuhai999@gmail.com）
 * @version V1.0
 * @Title: 文章前台页面Controller
 * @Package io.jpress.module.article.admin
 */
@RequestMapping("/article")
public class ArticleController extends TemplateControllerBase {

    @Inject
    private ArticleService articleService;

    @Inject
    private ArticleCategoryService categoryService;

    @Inject
    private OptionService optionService;

    @Inject
    private ArticleCommentService commentService;


    public void index() {
        Article article = getArticle();
        assertNotNull(article);

        //当文章处于审核中、草稿等的时候，显示404
        if (!article.isNormal()) {
            renderError(404);
            return;
        }

        if (StrUtils.isNotBlank(article.getLinkTo())) {
            redirect(article.getLinkTo());
            return;
        }

        //设置页面的seo信息
        setSeoInfos(article);


        //设置菜单高亮
        doFlagMenuActive(article);

        //记录当前浏览量
        articleService.doIncArticleViewCount(article.getId());

        setAttr("article", article);
        render(article.getHtmlView());
    }

    private void setSeoInfos(Article article) {
        setSeoTitle(article.getTitle());
        setSeoKeywords(article.getMetaKeywords());
        setSeoDescription(StrUtils.isBlank(article.getMetaDescription())
                ? CommonsUtils.maxLength(article.getText(), 100)
                : article.getMetaDescription());
    }


    private Article getArticle() {
        String idOrSlug = getPara(0);
        return StrUtils.isNumeric(idOrSlug)
                ? articleService.findById(idOrSlug)
                : articleService.findFirstBySlug(StrUtils.urlDecode(idOrSlug));
    }


    private void doFlagMenuActive(Article article) {

        setMenuActive(menu -> menu.getUrl().startsWith(article.getUrl()));


        List<ArticleCategory> articleCategories = categoryService.findActiveCategoryListByArticleId(article.getId());
        if (articleCategories == null || articleCategories.isEmpty()) {
            return;
        }

        setMenuActive(menu -> {
            if ("article_category".equals(menu.getRelativeTable())) {
                for (ArticleCategory category : articleCategories) {
                    if (category.getId().equals(menu.getRelativeId())) {
                        return true;
                    }
                }
            }
            return false;
        });

    }


    /**
     * 发布评论
     */
    public void postComment() {

        Long articleId = getParaToLong("articleId");
        Long pid = getParaToLong("pid");
        String nickname = getPara("nickname");
        String content = getPara("content");
        String email = getPara("email");
        String wechat = getPara("wechat");
        String qq = getPara("qq");

        if (articleId == null || articleId <= 0) {
            renderJson(Ret.fail());
            return;
        }

        if (StrUtils.isBlank(content)) {
            renderJson(Ret.fail().set("message", "评论内容不能为空"));
            return;
        } else {
            content = StringEscapeUtils.escapeHtml(content);
        }

        //是否对用户输入验证码进行验证
        Boolean vCodeEnable = optionService.findAsBoolByKey("article_comment_vcode_enable");
        if (vCodeEnable != null && vCodeEnable == true) {
            if (validateCaptcha("captcha") == false) {
                renderJson(Ret.fail().set("message", "验证码错误"));
                return;
            }
        }


        Article article = articleService.findById(articleId);
        if (article == null) {
            renderJson(Ret.fail());
            return;
        }

        // 文章关闭了评论的功能
        if (!article.isCommentEnable()) {
            renderJson(Ret.fail().set("message", "该文章的评论功能已关闭"));
            return;
        }

        //是否开启评论功能
        Boolean commentEnable = optionService.findAsBoolByKey("article_comment_enable");
        if (commentEnable == null || commentEnable == false) {
            renderJson(Ret.fail().set("message", "评论功能已关闭"));
            return;
        }


        //是否允许未登录用户参与评论
        Boolean unLoginEnable = optionService.findAsBoolByKey("article_comment_unlogin_enable");
        if (unLoginEnable == null || unLoginEnable == false) {
            if (getLoginedUser() == null) {
                renderJson(Ret.fail().set("message", "未登录用户不能评论").set("errorCode", 9));
                return;
            }
        }

        ArticleComment comment = new ArticleComment();

        comment.setArticleId(articleId);
        comment.setContent(content);
        comment.setAuthor(nickname);
        comment.setPid(pid);
        comment.setEmail(email);
        comment.setWechat(wechat);
        comment.setQq(qq);

        User user = getLoginedUser();
        if (user != null) {
            comment.setUserId(user.getId());
            comment.setAuthor(user.getNickname());
        }

        //是否是管理员必须审核
        Boolean reviewEnable = optionService.findAsBoolByKey("article_comment_review_enable");
        if (reviewEnable != null && reviewEnable == true) {
            comment.setStatus(ArticleComment.STATUS_UNAUDITED);
        }
        /**
         * 无需管理员审核、直接发布
         */
        else {
            comment.setStatus(ArticleComment.STATUS_NORMAL);
        }

        //记录文章的评论量
        articleService.doIncArticleCommentCount(articleId);

        if (pid != null) {
            //记录评论的回复数量
            commentService.doIncCommentReplyCount(pid);
        }
        commentService.saveOrUpdate(comment);

        Ret ret = Ret.ok();
        if (comment.isNormal()) {
            ret.set("comment", comment).set("code", 0);
        } else {
            ret.set("code", 0);
        }

        if (user != null) {
            ret.put("user", user.keepSafe());
        }

        renderJson(ret);

        ArticleKit.doNotifyAdministratorByEmail(article, comment);
    }


}
