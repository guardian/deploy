selectedProject = ''
menuOpen = false

updateBuildInfo = (buildNumber) ->
  $('#build-info').load(jsRoutes.controllers.DeployController.buildInfo(selectedProject, buildNumber).url)

updateDeployInfo = () ->
  elemProjectInput = $('#projectInput')
  isExactMatch = elemProjectInput.hasClass("project-exact-match")
  selectedProject = elemProjectInput.val()
  selectedStage = $('#stage').val()

  url = if selectedStage == ''
          jsRoutes.controllers.DeployController.deployHistory(selectedProject, undefined, isExactMatch).url
        else
          jsRoutes.controllers.DeployController.deployHistory(selectedProject, selectedStage, isExactMatch).url
  $('#deploy-info').load(
    url,
    ->
      $('tbody.rowlink').rowlink()
      $("[rel='tooltip']").tooltip()
  )

readFavouriteProjects = () ->
  JSON.parse(localStorage.getItem('favouriteProjects'))

writeFavouriteProjects = (newFavourites) ->
  localStorage.setItem('favouriteProjects', JSON.stringify(newFavourites))

addFavouriteProject = (project) ->
  favourites = readFavouriteProjects()
  newFavourites =
    if favourites?
      projectAlreadyFavourited = favourites.includes(project)
      if !projectAlreadyFavourited
        favourites.push(project)

      favourites
    else
      [project]
  writeFavouriteProjects(newFavourites)
  renderFavourites()

deleteFavourite = (project) ->
  favourites = readFavouriteProjects()
  newFavourites =
    if favourites?
      favourites.filter (fav) -> fav != project
    else
      []
  writeFavouriteProjects(newFavourites)
  renderFavourites()

setupFavouriteHandlers = () ->
  $('.delete-favourite-project-button').click (e) ->
    e.preventDefault()
    selectedProject = e.currentTarget.value
    if selectedProject?
      deleteFavourite(selectedProject)

  $('.select-favourite-project-button').click (e) ->
    e.preventDefault()
    project = e.target.value

    elemProjectInput = $('#projectInput')
    elemProjectInput.val(project)
    updateDeployInfo()

renderFavourites = () ->
  container = $('#favourites-container')
  favourites = readFavouriteProjects()
  if favourites? && favourites.length > 0
    container.removeClass('hidden')
    list = $('#favourites-list', container)
    list.empty()
    favourites.forEach (fav) ->
      list.append("
          <div class=\"favourite\">
            <button class=\"select-favourite-project-button btn btn-default\" value=\"#{fav}\" aria-label=\"Use favourite: #{fav}\" title=\"Use favourite: #{fav}\">#{fav}</button>
            <button class=\"delete-favourite-project-button btn btn-xs btn-danger\" value=\"#{fav}\" aria-label=\"Delete favourite: #{fav}\" title=\"Delete favourite: #{fav}\">
              <i class=\"glyphicon glyphicon-trash glyphicon glyphicon-white\"></i>
            </button>
          </div>
      ")
    setupFavouriteHandlers()
  else
    container.addClass('hidden')

readFavouriteStage = () ->
  localStorage.getItem('favouriteStage')

writeFavouriteStage = (newFavourite) ->
  localStorage.setItem('favouriteStage', newFavourite)
  favouriteStageSelected()

removeFavouriteStage = () ->
  localStorage.removeItem('favouriteStage')
  favouriteStageNotSelected()

updateFavouriteStageButton = () ->
  currentStage = $('#stage').val()
  favouriteStage = readFavouriteStage()

  if (favouriteStage == currentStage)
    favouriteStageSelected()
  else
    favouriteStageNotSelected()

favouriteStageNotSelected = () ->
  $('#add-favourite-stage-button').removeClass('hidden');
  $('#remove-favourite-stage-button').addClass('hidden');

favouriteStageSelected = () ->
  $('#add-favourite-stage-button').addClass('hidden');
  $('#remove-favourite-stage-button').removeClass('hidden');

$ ->
  $('#projectInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source:serverUrl
      minLength:0

    addFavouriteProjectButton = $('#add-favourite-project-button')
    updateFavouriteButton = ->
      projectInputIsEmpty = input.val().trim() == ''
      addFavouriteProjectButton.prop('disabled', projectInputIsEmpty)

    input.on('change', updateFavouriteButton)
    input.on('keyup', updateFavouriteButton)

  $('#projectInput').blur updateDeployInfo

  $('#buildInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source: (request,response) ->
        $.getJSON(
          serverUrl,
          {term: request.term.split( /,\s*/).pop(), project: selectedProject},
          response
        )
      open: (event,ui) -> menuOpen = true
      close: (event,ui) ->
        menuOpen = false
        updateBuildInfo( input.val() )
      select: (event,ui) ->
        updateBuildInfo( input.val() )
      minLength:0

  $('#buildInput').on('input keyup',
    ->
      input = $(this)
      updateBuildInfo( input.val() )
  )

  $('#buildInput').focus (e) ->
    if (!menuOpen)
      $(e.target).autocomplete("search")

  $('#stage').each ->
    stageInput = $(this)

    favouriteStage = readFavouriteStage()
    if (favouriteStage)
      stageInput.val(favouriteStage)
      favouriteStageSelected()
      updateDeployInfo()

  $('#stage').change ->
    updateFavouriteStageButton()
    updateDeployInfo()

  updateDeployInfo()

  $('#add-favourite-project-button').click (e) ->
    e.preventDefault()

    elemProjectInput = $('#projectInput')
    selectedProject = elemProjectInput.val()

    if selectedProject
      addFavouriteProject(selectedProject)

  $('#add-favourite-stage-button').click (e) ->
    e.preventDefault()

    elemProjectInput = $('#stage')
    selectedStage = elemProjectInput.val()

    if selectedStage
      writeFavouriteStage(selectedStage)

  $('#remove-favourite-stage-button').click (e) ->
    e.preventDefault()
    removeFavouriteStage()

  renderFavourites()

  console.log('initialised')
