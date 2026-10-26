declare namespace API {
  type ArticleAiModifyOutlineRequest = {
    taskId?: string;
    modifySuggestion?: string;
  };

  type ArticleConfirmOutlineRequest = {
    taskId?: string;
    outline?: OutlineSection[];
  };

  type ArticleConfirmTitleRequest = {
    taskId?: string;
    selectedMainTitle?: string;
    selectedSubTitle?: string;
    userDescription?: string;
  };

  type ArticleCreateRequest = {
    topic?: string;
    style?: string;
    enabledImageMethods?: string[];
  };

  type ArticleQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    userId?: string;
    status?: string;
  };

  type ArticleVO = {
    id?: string;
    taskId?: string;
    userId?: string;
    topic?: string;
    mainTitle?: string;
    subTitle?: string;
    /** 当前阶段：PENDING/TITLE_GENERATING/TITLE_SELECTING/OUTLINE_GENERATING/OUTLINE_EDITING/CONTENT_GENERATING */
    phase?: string;
    /** 标题方案列表（TITLE_SELECTING 阶段用于恢复界面） */
    titleOptions?: TitleOption[];
    userDescription?: string;
    outline?: OutlineItem[];
    content?: string;
    images?: ImageItem[];
    status?: string;
    errorMessage?: string;
    createTime?: string;
    completedTime?: string;
  };

  type TitleOption = {
    mainTitle?: string;
    subTitle?: string;
  };

  type BaseResponseArticleVO = {
    code?: number;
    data?: ArticleVO;
    message?: string;
  };

  type BaseResponseBoolean = {
    code?: number;
    data?: boolean;
    message?: string;
  };

  type BaseResponseListOutlineSection = {
    code?: number;
    data?: OutlineSection[];
    message?: string;
  };

  type BaseResponseLoginUserVO = {
    code?: number;
    data?: LoginUserVO;
    message?: string;
  };

  type BaseResponseLong = {
    code?: number;
    data?: number;
    message?: string;
  };

  type BaseResponsePageArticleVO = {
    code?: number;
    data?: PageArticleVO;
    message?: string;
  };

  type BaseResponsePageUserVO = {
    code?: number;
    data?: PageUserVO;
    message?: string;
  };

  type BaseResponseString = {
    code?: number;
    data?: string;
    message?: string;
  };

  type BaseResponseUser = {
    code?: number;
    data?: User;
    message?: string;
  };

  type BaseResponseUserVO = {
    code?: number;
    data?: UserVO;
    message?: string;
  };

  type BaseResponseVoid = {
    code?: number;
    data?: any;
    message?: string;
  };

  type DeleteRequest = {
    id?: string;
  };

  type getArticleParams = {
    taskId: string;
  };

  type getProgressParams = {
    taskId: string;
  };

  type getUserByIdParams = {
    id: number;
  };

  type getUserVOByIdParams = {
    id: number;
  };

  type ImageItem = {
    position?: number;
    url?: string;
    method?: string;
    keywords?: string;
    description?: string;
  };

  type LoginUserVO = {
    id?: string;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
    updateTime?: string;
  };

  type OutlineItem = {
    section?: number;
    title?: string;
    points?: string[];
  };

  type OutlineSection = {
    section?: number;
    title?: string;
    points?: string[];
  };

  type PageArticleVO = {
    records?: ArticleVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PageUserVO = {
    records?: UserVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type SseEmitter = {
    timeout?: number;
  };

  type User = {
    id?: number;
    userAccount?: string;
    userPassword?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    quota?: number;
    editTime?: string;
    createTime?: string;
    updateTime?: string;
    isDelete?: number;
  };

  type UserAddRequest = {
    userName?: string;
    userAccount?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserLoginRequest = {
    userAccount: string;
    userPassword: string;
  };

  type UserQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    id?: string;
    userName?: string;
    userAccount?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserRegisterRequest = {
    userAccount: string;
    userPassword: string;
    checkPassword: string;
  };

  type UserUpdateRequest = {
    id?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserVO = {
    id?: string;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
  };
}
